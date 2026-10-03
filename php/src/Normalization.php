<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\Composing;
use Raoh\Notation199x\Internal\FormFacts;
use Raoh\Notation199x\Internal\NormalizationTables;

/**
 * Unicode 18.0.0 normalization: NFC, NFD, NFKC and NFKD.
 *
 * Not Normalizer from intl, which answers for the Unicode version of the ICU it was built with,
 * and moves with an update of the system it runs on.
 *
 * The algorithm is the standard three steps of UAX #15: decompose fully, by the tables and by
 * Hangul's arithmetic, put combining marks in canonical order, and in a composing form compose
 * canonically wherever nothing blocks it. The compatibility forms decompose by the compatibility
 * mappings as well as the canonical ones; composition is canonical in every form. Every text here
 * is valid UTF-8 (see ScalarValues::invalidUtf8At); none of these asks.
 */
final class Normalization
{
    private const ASCII = "\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0A\x0B\x0C\x0D\x0E\x0F"
        . "\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1A\x1B\x1C\x1D\x1E\x1F"
        . " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\x7F";

    private function __construct()
    {
    }

    /**
     * $s in $form, by Unicode 18.0.0's data.
     */
    public static function normalize(NormalizationForm $form, string $s): string
    {
        return self::normalizeCore($form, $s, -1) ?? throw new \LogicException('a normalization without a bound');
    }

    /**
     * normalize($form, $s) where that is no longer than $longest scalar values, and null where it
     * is longer, which is found out before more than $longest is written. A negative bound is one
     * no text is within.
     */
    public static function normalizeWithin(NormalizationForm $form, string $s, int $longest): ?string
    {
        return $longest < 0 ? null : self::normalizeCore($form, $s, $longest);
    }

    /**
     * $s in $form, and null where that is longer than $longest; a negative $longest is no bound.
     *
     * Text that is its own normalization is answered with itself. The text is kept as it is up to
     * the first character that is not a stable starter of the form: a starter whose quick check
     * for the form is Yes (NormalizationTables::STABLE_PAGES). From the stable starter before that
     * one, which what follows it may compose with, the algorithm is run up to the next stable
     * starter, and the text is kept as it is again from there. A stable starter composes with
     * nothing before it and blocks every mark after it from reaching a starter before it, so what
     * comes before it is settled when it is read.
     *
     * Two things are kept as the text is read: where the next run the algorithm goes over would
     * begin, the last stable starter read or, where none has been read since the last such run,
     * where the text is read to; and how many scalar values of the answer come before that. A run
     * is written into the answer only where it changed what it went over, and the answer is made
     * only once one has: up to there it is the text.
     *
     * What a normalization does that grows with the text is done in these loops and in no call to
     * PHP, so a checkpoint added later asks in each of them: normalizeCore() over the runs of the
     * text; stableUpTo() over the stable starters, a character at a time apart from a run of ASCII;
     * Composing::run() over a run the algorithm goes over, a character at a time;
     * Composing::settle() and Composing::order() over a combining run, which may be as long as the
     * text; and Composing::write() over the marks of a run it writes.
     *
     * LoopsTest holds this list to every loop a normalization reaches, apart from those it says are
     * bounded whatever the text, and holds what a normalization calls of PHP to a list.
     */
    private static function normalizeCore(NormalizationForm $form, string $s, int $longest): ?string
    {
        $facts = FormFacts::of($form);
        $length = strlen($s);
        // The answer up to $kept, where a run has changed what it went over.
        $out = null;
        $kept = 0;
        $start = 0;
        // The scalar values of the answer before the last run the algorithm went over, and the
        // stable starters read since, the last of them at $start where $start is before $at.
        $before = 0;
        $read = 0;
        $composing = null;
        for ($at = 0; $at < $length;) {
            $stable = self::stableUpTo($s, $at, $length, $facts->limit, $facts->bit, $read, $start);
            if ($stable === $length) {
                break;
            }
            if ($start < $stable) {
                $read--;
            }
            $composing ??= new Composing($facts, $longest);
            $end = $composing->run($s, $start, $stable, $before + $read);
            if ($end < 0) {
                return null;
            }
            $written = $composing->answer();
            if ($written !== substr($s, $start, $end - $start)) {
                $out ??= '';
                $out .= substr($s, $kept, $start - $kept);
                $out .= $written;
                $kept = $end;
            }
            $before = $composing->written();
            $read = 0;
            $start = $end;
            $at = $end;
        }
        if ($longest >= 0 && $before + $read > $longest) {
            return null;
        }
        return $out === null ? $s : $out . substr($s, $kept);
    }

    /**
     * Where the stable starters of the form that $s has from $at end: the first character from
     * there that is not one, or the end of the text. Adds how many it read to $read, and sets
     * $last to where the last of them begins where it read any.
     *
     * A step of its loop is a run of ASCII, which is below every form's limit (NormalizationTest
     * holds the tables to that), gone past whole, or one character. A character is decoded from
     * its bytes where it is, and not taken out of the text first.
     */
    private static function stableUpTo(string $s, int $at, int $length, int $limit, int $bit, int &$read, int &$last): int
    {
        $blocks = NormalizationTables::STABLE_BLOCKS;
        $pages = NormalizationTables::STABLE_PAGES;
        while ($at < $length) {
            $b0 = ord($s[$at]);
            if ($b0 < 0x80) {
                // A checkpoint added later bounds the run by strspn's length.
                $run = strspn($s, self::ASCII, $at, $length - $at);
                $read += $run;
                $at += $run;
                $last = $at - 1;
                continue;
            }
            if ($b0 >= 0xE0 && $b0 < 0xF0) {
                $cp = (($b0 & 0x0F) << 12) | ((ord($s[$at + 1] ?? "\x80") & 0x3F) << 6)
                    | (ord($s[$at + 2] ?? "\x80") & 0x3F);
                $width = 3;
            } elseif ($b0 < 0xC0) {
                // Not the first byte of a character, which valid UTF-8 has none of here.
                $cp = $b0;
                $width = 1;
            } elseif ($b0 < 0xE0) {
                $cp = (($b0 & 0x1F) << 6) | (ord($s[$at + 1] ?? "\x80") & 0x3F);
                $width = 2;
            } else {
                $cp = (($b0 & 0x07) << 18) | ((ord($s[$at + 1] ?? "\x80") & 0x3F) << 12)
                    | ((ord($s[$at + 2] ?? "\x80") & 0x3F) << 6) | (ord($s[$at + 3] ?? "\x80") & 0x3F);
                $width = 4;
            }
            if ($cp >= $limit && $cp <= 0x10FFFF && (ord($pages[ord($blocks[$cp >> 8]) << 8 | $cp & 0xFF]) & $bit) === 0) {
                return $at;
            }
            $read++;
            $last = $at;
            $at += $width;
        }
        return $at;
    }
}
