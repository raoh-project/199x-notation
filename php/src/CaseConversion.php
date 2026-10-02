<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\CaseTables;
use Raoh\Notation199x\Internal\Ranges;
use Raoh\Notation199x\Internal\Utf8;

/**
 * Unicode 18.0.0 default case conversion: the untailored full mapping, from UnicodeData.txt and
 * SpecialCasing.txt, with no locale or language tailoring, and the Final_Sigma condition.
 *
 * Not mb_strtolower or mb_strtoupper, which map by the Unicode version of the PHP they were built
 * with, and not strtolower, which maps ASCII alone.
 *
 * A case mapping is not closed under any normalization form, so a caller that holds its text in
 * one normalizes the answer. Every text here is valid UTF-8 (see ScalarValues::invalidUtf8At); none
 * of these asks.
 */
final class CaseConversion
{
    /**
     * For the lowercase and the uppercase mapping, the bytes the mapping may change the character
     * of: every byte that begins or continues a character past ASCII, and the ASCII characters the
     * tables name. Read off the tables, so that a run of other bytes is gone past at once without
     * a rule of its own about ASCII.
     *
     * @var array{0?: string, 1?: string}
     */
    private static array $stops = [];

    private function __construct()
    {
    }

    /**
     * $s in lowercase. One code point can map to several. A Greek capital sigma becomes the final
     * form only at the end of a cased run, which is the one condition the default mapping carries
     * that is context rather than locale: lowercase("ΟΣ") is "ος" and lowercase("ΟΣΑ") is "οσα".
     */
    public static function lowercase(string $s): string
    {
        return self::map($s, true, -1) ?? throw new \LogicException('a conversion without a bound');
    }

    /**
     * $s in uppercase, by the same untailored full mapping as lowercase: one code point can widen
     * to several, so uppercase("straße") is "STRASSE", and no locale narrows it, so a Turkish i
     * still becomes I.
     */
    public static function uppercase(string $s): string
    {
        return self::map($s, false, -1) ?? throw new \LogicException('a conversion without a bound');
    }

    /**
     * lowercase($s) where that is no longer than $longest scalar values, and null where it is
     * longer, which is found out before more than $longest is written. A negative bound is one no
     * text is within.
     */
    public static function lowercaseWithin(string $s, int $longest): ?string
    {
        return $longest < 0 ? null : self::map($s, true, $longest);
    }

    /**
     * uppercase($s) where that is no longer than $longest scalar values, and null where it is
     * longer, which is found out before more than $longest is written. A negative bound is one no
     * text is within.
     */
    public static function uppercaseWithin(string $s, int $longest): ?string
    {
        return $longest < 0 ? null : self::map($s, false, $longest);
    }

    /**
     * The mapped text, and null where it is longer than $longest; a negative $longest is no bound.
     *
     * The one loop every form of the conversion runs, a step at a time: a step is a run of bytes
     * no table names, gone past whole, or one character, mapped. The text is read where it is:
     * Final_Sigma looks either side of a sigma for as many Case_Ignorable code points as there are,
     * and looks at them in the text. What is bounded is what is written: each character's mapping
     * is measured before any of it is, so the answer never holds more than $longest, nor part of a
     * mapping that would take it past.
     *
     * Text that maps to itself is answered with itself. Otherwise what maps to itself is copied a
     * run at a time, from $kept, and the answer is made only once a character that changes is met.
     */
    private static function map(string $s, bool $lower, int $longest): ?string
    {
        $table = $lower ? CaseTables::LOWER : CaseTables::UPPER;
        $stops = self::stops($lower);
        $length = strlen($s);
        $out = '';
        $changed = false;
        $kept = 0;
        $written = 0;
        for ($at = 0; $at < $length;) {
            // A run of ASCII the mapping leaves as it is: one scalar value a byte.
            $run = strcspn($s, $stops, $at);
            if ($run > 0) {
                if ($longest >= 0 && $run > $longest - $written) {
                    return null;
                }
                $written += $run;
                $at += $run;
                continue;
            }
            $width = Utf8::width(ord($s[$at]));
            $character = substr($s, $at, $width);
            $after = $at + $width;
            $to = null;
            if ($lower && isset(CaseTables::FINAL_SIGMA[$character]) && self::isFinalSigma($s, $at, $after)) {
                $to = CaseTables::FINAL_SIGMA[$character];
            }
            $to ??= $table[$character] ?? null;
            $adding = $to === null ? 1 : Utf8::countShort($to);
            if ($longest >= 0 && $adding > $longest - $written) {
                return null;
            }
            $written += $adding;
            if ($to !== null) {
                $out .= substr($s, $kept, $at - $kept) . $to;
                $changed = true;
                $kept = $after;
            }
            $at = $after;
        }
        if (!$changed) {
            return $s;
        }
        return $out . substr($s, $kept);
    }

    private static function stops(bool $lower): string
    {
        $which = $lower ? 0 : 1;
        if (!isset(self::$stops[$which])) {
            $stops = '';
            for ($byte = 0x80; $byte <= 0xFF; $byte++) {
                $stops .= chr($byte);
            }
            $named = $lower ? CaseTables::LOWER + CaseTables::FINAL_SIGMA : CaseTables::UPPER;
            foreach (array_keys($named) as $character) {
                if (strlen($character) === 1) {
                    $stops .= $character;
                }
            }
            self::$stops[$which] = $stops;
        }
        return self::$stops[$which];
    }

    /**
     * Unicode's Final_Sigma condition of the character between $at and $after: preceded, skipping
     * Case_Ignorable code points, by a Cased one, and not followed, skipping the same way, by
     * another Cased one. Scanned as far as the text goes rather than over a window, since what is
     * skipped is decided by the property and not by a count.
     */
    private static function isFinalSigma(string $s, int $at, int $after): bool
    {
        $precededByCased = false;
        for ($j = $at; $j > 0;) {
            // Back over the continuation bytes to where the character before begins.
            do {
                $j--;
            } while ($j > 0 && (ord($s[$j]) & 0xC0) === 0x80);
            $cp = Utf8::decodeAt($s, $j);
            if (Ranges::has(CaseTables::CASE_IGNORABLE, $cp)) {
                continue;
            }
            $precededByCased = Ranges::has(CaseTables::CASED, $cp);
            break;
        }
        if (!$precededByCased) {
            return false;
        }
        $length = strlen($s);
        for ($j = $after; $j < $length;) {
            $width = Utf8::width(ord($s[$j]));
            $cp = Utf8::decode(substr($s, $j, $width));
            $j += $width;
            if (Ranges::has(CaseTables::CASE_IGNORABLE, $cp)) {
                continue;
            }
            return !Ranges::has(CaseTables::CASED, $cp);
        }
        return true;
    }
}
