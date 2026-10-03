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
     * For the lowercase and the uppercase mapping: the bytes the mapping may change the character
     * of, every byte that begins or continues a character past ASCII and the ASCII characters the
     * tables name; the ASCII characters the mapping makes another ASCII character, and no
     * Final_Sigma entry names; and those characters, and what each is made, as strtr takes them.
     * Read off the tables, so that a run of other bytes is gone past at once, and a run of ASCII
     * the mapping changes is mapped at once, without a rule of its own about ASCII.
     *
     * @var array<int, array{string, string, string, string}>
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
     * longer, which is found out before more than $longest is written. How much of $s it reads
     * turns on $longest and not on the length of $s: it reads no further once what it has read
     * shows the answer to be longer. A negative bound is one no text is within.
     */
    public static function lowercaseWithin(string $s, int $longest): ?string
    {
        return $longest < 0 ? null : self::map($s, true, $longest);
    }

    /**
     * uppercase($s) where that is no longer than $longest scalar values, and null where it is
     * longer, which is found out before more than $longest is written. How much of $s it reads
     * turns on $longest and not on the length of $s: it reads no further once what it has read
     * shows the answer to be longer. A negative bound is one no text is within.
     */
    public static function uppercaseWithin(string $s, int $longest): ?string
    {
        return $longest < 0 ? null : self::map($s, false, $longest);
    }

    /**
     * The mapped text, and null where it is longer than $longest; a negative $longest is no bound.
     *
     * Every form of the conversion runs this, a step at a time: a step is a run of bytes no table
     * names, gone past whole, a run of ASCII the mapping makes other ASCII, mapped whole, or one
     * character, mapped, which is taken one after another up to the next ASCII byte. The text is read where it is:
     * Final_Sigma looks either side of a sigma for as many Case_Ignorable code points as there are,
     * and looks at them in the text. What is bounded is what is written: each character's mapping
     * is measured before any of it is, so the answer never holds more than $longest, nor part of a
     * mapping that would take it past. Every character maps to at least one, which the generator
     * checks, so the text is read no further than one character past $longest either: strcspn and
     * strspn are given no more than that, and Final_Sigma looks past no more Case_Ignorable
     * characters after a sigma than the answer has room for.
     *
     * Text that maps to itself is answered with itself. Otherwise what maps to itself is copied a
     * run at a time, from $kept, and the answer is made only once a character that changes is met.
     *
     * What a conversion does that grows with the text is done in these loops and in no call to
     * PHP, so a checkpoint added later asks in each of them: map() over the text, and
     * isFinalSigma() either side of a sigma, as far as the text goes.
     *
     * LoopsTest holds this list to every loop a conversion reaches, apart from those it says are
     * bounded whatever the text, and holds what a conversion calls of PHP to a list.
     */
    private static function map(string $s, bool $lower, int $longest): ?string
    {
        $table = $lower ? CaseTables::LOWER : CaseTables::UPPER;
        [$stops, $changing, $asciiFrom, $asciiTo] = self::stops($lower);
        $length = strlen($s);
        $out = '';
        $changed = false;
        $kept = 0;
        $written = 0;
        for ($at = 0; $at < $length;) {
            // As many bytes as the answer has room for and one more, which each is at least one
            // scalar value of.
            $most = $longest < 0 || $longest - $written + 1 >= $length - $at ? $length - $at : $longest - $written + 1;
            // A run of ASCII the mapping leaves as it is: one scalar value a byte. A checkpoint
            // added later bounds the run by strcspn's length.
            $run = strcspn($s, $stops, $at, $most);
            if ($run > 0) {
                if ($longest >= 0 && $run > $longest - $written) {
                    return null;
                }
                $written += $run;
                $at += $run;
                continue;
            }
            // A run of ASCII the mapping makes other ASCII: one scalar value a byte, mapped in one
            // call. A checkpoint added later bounds the run by strspn's length.
            $run = strspn($s, $changing, $at, $most);
            if ($run > 0) {
                if ($longest >= 0 && $run > $longest - $written) {
                    return null;
                }
                $written += $run;
                $out .= substr($s, $kept, $at - $kept) . strtr(substr($s, $at, $run), $asciiFrom, $asciiTo);
                $changed = true;
                $at += $run;
                $kept = $at;
                continue;
            }
            // Characters past ASCII, one at a time, until the next ASCII byte.
            do {
                $width = Utf8::width(ord($s[$at]));
                $character = substr($s, $at, $width);
                $after = $at + $width;
                $to = null;
                if ($lower && isset(CaseTables::FINAL_SIGMA[$character])) {
                    // The sigma is at least one scalar value of the answer, as every character is,
                    // and each Case_Ignorable one after it is another.
                    if ($longest >= 0 && $written >= $longest) {
                        return null;
                    }
                    $final = self::isFinalSigma($s, $at, $after, $longest < 0 ? -1 : $longest - $written - 1);
                    if ($final === null) {
                        return null;
                    }
                    if ($final) {
                        $to = CaseTables::FINAL_SIGMA[$character];
                    }
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
            } while ($at < $length && ord($s[$at]) >= 0x80);
        }
        if (!$changed) {
            return $s;
        }
        return $out . substr($s, $kept);
    }

    /**
     * @return array{string, string, string, string}
     */
    private static function stops(bool $lower): array
    {
        $which = $lower ? 0 : 1;
        if (!isset(self::$stops[$which])) {
            $stops = '';
            for ($byte = 0x80; $byte <= 0xFF; $byte++) {
                $stops .= chr($byte);
            }
            $changing = '';
            $to = '';
            // Each ASCII character asked of the tables, rather than every key read: a request
            // works this out once, and the 128 lookups cost a few microseconds.
            for ($byte = 0; $byte < 0x80; $byte++) {
                $character = chr($byte);
                $mapped = ($lower ? CaseTables::LOWER : CaseTables::UPPER)[$character] ?? null;
                if ($mapped !== null || $lower && isset(CaseTables::FINAL_SIGMA[$character])) {
                    $stops .= $character;
                }
                if ($mapped !== null && strlen($mapped) === 1 && ord($mapped) < 0x80
                    && !($lower && isset(CaseTables::FINAL_SIGMA[$character]))) {
                    $changing .= $character;
                    $to .= $mapped;
                }
            }
            self::$stops[$which] = [$stops, $changing, $changing, $to];
        }
        return self::$stops[$which];
    }

    /**
     * Unicode's Final_Sigma condition of the character between $at and $after: preceded, skipping
     * Case_Ignorable code points, by a Cased one, and not followed, skipping the same way, by
     * another Cased one. Scanned as far as the text goes rather than over a window, since what is
     * skipped is decided by the property and not by a count. Null where $most is not negative and
     * more than $most Case_Ignorable characters follow it, which leaves it undecided.
     */
    private static function isFinalSigma(string $s, int $at, int $after, int $most): ?bool
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
        $skipped = 0;
        for ($j = $after; $j < $length;) {
            $width = Utf8::width(ord($s[$j]));
            $cp = Utf8::decode(substr($s, $j, $width));
            $j += $width;
            if (Ranges::has(CaseTables::CASE_IGNORABLE, $cp)) {
                if ($skipped === $most) {
                    return null;
                }
                $skipped++;
                continue;
            }
            return !Ranges::has(CaseTables::CASED, $cp);
        }
        return true;
    }
}
