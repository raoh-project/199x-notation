<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\Composing;
use Raoh\Notation199x\Internal\NormalizationTables;
use Raoh\Notation199x\Internal\Utf8;

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
     * Text made only of code points below the form's trivial limit is its own normalization, and is
     * answered with itself. Other text is normalized from the last code point below the limit
     * before the first one that is not, and what comes before that is kept as it is: it is its own
     * normalization, and nothing from there on reaches back into it, since the code point there is
     * a starter that composes with nothing before it and blocks every mark after it from composing
     * with a starter before it. That code point is normalized with the rest, since what follows it
     * may compose with it.
     */
    private static function normalizeCore(NormalizationForm $form, string $s, int $longest): ?string
    {
        $limit = match ($form) {
            NormalizationForm::NFC => NormalizationTables::NFC_TRIVIAL_LIMIT,
            NormalizationForm::NFD => NormalizationTables::NFD_TRIVIAL_LIMIT,
            NormalizationForm::NFKC => NormalizationTables::NFKC_TRIVIAL_LIMIT,
            NormalizationForm::NFKD => NormalizationTables::NFKD_TRIVIAL_LIMIT,
        };
        $length = strlen($s);
        $last = 0;
        $beforeLast = 0;
        $read = 0;
        for ($at = 0; $at < $length;) {
            // A run of ASCII, which is below every form's limit (NormalizationTest holds the
            // tables to that).
            $run = strspn($s, self::ASCII, $at);
            if ($run > 0) {
                $last = $at + $run - 1;
                $beforeLast = $read + $run - 1;
                $read += $run;
                $at += $run;
                continue;
            }
            $width = Utf8::width(ord($s[$at]));
            if (Utf8::decode(substr($s, $at, $width)) >= $limit) {
                return self::normalizeFrom($form, $s, $last, $beforeLast, $longest);
            }
            $last = $at;
            $beforeLast = $read;
            $read++;
            $at += $width;
        }
        if ($longest >= 0 && $read > $longest) {
            return null;
        }
        return $s;
    }

    /**
     * The algorithm from the text's start, taking the text before $from, $kept scalar values long,
     * as it is.
     *
     * The three steps are taken one combining run at a time, as the text is read: each character
     * is decomposed as it arrives, the marks after a starter are held until the next starter, and
     * then they are put in canonical order and, in a composing form, composed into it. Canonical
     * ordering never moves a mark past a starter, and composition joins a starter only to the marks
     * after it or, where nothing is between them, to the starter after it, so a run settled when
     * the next starter arrives is settled as the whole text's algorithm would settle it. What is
     * held at once is one run's marks, never the decomposition of the whole text.
     *
     * The one loop every form of normalization past its trivial limit runs: a step is one
     * character of the text, decomposed and taken.
     */
    private static function normalizeFrom(NormalizationForm $form, string $s, int $from, int $kept, int $longest): ?string
    {
        if ($longest >= 0 && $kept > $longest) {
            return null;
        }
        $compatibility = $form === NormalizationForm::NFKC || $form === NormalizationForm::NFKD;
        $composes = $form === NormalizationForm::NFC || $form === NormalizationForm::NFKC;
        $c = new Composing($composes, $longest, substr($s, 0, $from), $kept);
        $length = strlen($s);
        for ($at = $from; $at < $length;) {
            $width = Utf8::width(ord($s[$at]));
            $character = substr($s, $at, $width);
            $at += $width;
            $parts = Composing::decompose($character, $compatibility);
            if ($parts === null) {
                if (!$c->take($character)) {
                    return null;
                }
                continue;
            }
            foreach ($parts as $part) {
                if (!$c->take($part)) {
                    return null;
                }
            }
        }
        return $c->finish();
    }
}
