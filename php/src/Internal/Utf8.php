<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

/**
 * A character of a string as the bytes UTF-8 writes it in, and the code point those bytes are.
 *
 * Each of these but scalarAt takes bytes that are valid UTF-8 and does not ask: whether a string is
 * is asked once, by ScalarValues::invalidUtf8At, where text is taken in. On bytes that are not, each
 * answers something and goes on, so a loop over them ends. scalarAt asks, for what reads bytes no
 * one has asked of: a pattern as it is read, and a subject as it is matched.
 *
 * @internal
 */
final class Utf8
{
    /**
     * How many bytes the character whose first byte is $lead takes.
     */
    public static function width(int $lead): int
    {
        return match (true) {
            $lead < 0xC0 => 1,
            $lead < 0xE0 => 2,
            $lead < 0xF0 => 3,
            default => 4,
        };
    }

    /**
     * The code point the one character $c writes.
     */
    public static function decode(string $c): int
    {
        $b0 = ord($c[0]);
        $width = strlen($c);
        if ($b0 < 0x80 || $width < 2) {
            return $b0;
        }
        if ($b0 < 0xE0) {
            return (($b0 & 0x1F) << 6) | (ord($c[1]) & 0x3F);
        }
        if ($b0 < 0xF0 || $width < 4) {
            return (($b0 & 0x0F) << 12) | ((ord($c[1]) & 0x3F) << 6) | (ord($c[2] ?? "\x80") & 0x3F);
        }
        return (($b0 & 0x07) << 18) | ((ord($c[1]) & 0x3F) << 12) | ((ord($c[2]) & 0x3F) << 6)
            | (ord($c[3]) & 0x3F);
    }

    /**
     * The code point of the character that begins at byte $at of $s.
     */
    public static function decodeAt(string $s, int $at): int
    {
        return self::decode(substr($s, $at, self::width(ord($s[$at]))));
    }

    /**
     * The scalar value whose UTF-8 begins at byte $at of $s, and how many bytes it takes; or -1 and
     * one byte where the bytes there are not UTF-8. A surrogate written in UTF-8's form, a sequence
     * longer than it has to be and one past U+10FFFF are not.
     *
     * @return array{int, int}
     */
    public static function scalarAt(string $s, int $at): array
    {
        $b0 = ord($s[$at]);
        if ($b0 < 0x80) {
            return [$b0, 1];
        }
        // The least and the greatest second byte each first byte allows, as RFC 3629 states them:
        // what keeps out an overlong form, a surrogate and what is past U+10FFFF.
        [$width, $low, $high, $bits] = match (true) {
            $b0 >= 0xC2 && $b0 <= 0xDF => [2, 0x80, 0xBF, $b0 & 0x1F],
            $b0 === 0xE0 => [3, 0xA0, 0xBF, 0],
            $b0 >= 0xE1 && $b0 <= 0xEC, $b0 === 0xEE, $b0 === 0xEF => [3, 0x80, 0xBF, $b0 & 0x0F],
            $b0 === 0xED => [3, 0x80, 0x9F, 0x0D],
            $b0 === 0xF0 => [4, 0x90, 0xBF, 0],
            $b0 >= 0xF1 && $b0 <= 0xF3 => [4, 0x80, 0xBF, $b0 & 0x07],
            $b0 === 0xF4 => [4, 0x80, 0x8F, 0x04],
            default => [0, 0, 0, 0],
        };
        if ($width === 0 || $at + $width > strlen($s)) {
            return [-1, 1];
        }
        $b1 = ord($s[$at + 1]);
        if ($b1 < $low || $b1 > $high) {
            return [-1, 1];
        }
        $cp = ($bits << 6) | ($b1 & 0x3F);
        for ($i = 2; $i < $width; $i++) {
            $b = ord($s[$at + $i]);
            if (($b & 0xC0) !== 0x80) {
                return [-1, 1];
            }
            $cp = ($cp << 6) | ($b & 0x3F);
        }
        return [$cp, $width];
    }

    /**
     * The bytes UTF-8 writes the scalar value $cp in.
     */
    public static function encode(int $cp): string
    {
        // Each byte is masked to the bits it carries, which on a scalar value changes nothing.
        if ($cp < 0x80) {
            return chr($cp & 0x7F);
        }
        if ($cp < 0x800) {
            return chr(0xC0 | (($cp >> 6) & 0x1F)) . chr(0x80 | ($cp & 0x3F));
        }
        if ($cp < 0x10000) {
            return chr(0xE0 | (($cp >> 12) & 0x0F)) . chr(0x80 | (($cp >> 6) & 0x3F)) . chr(0x80 | ($cp & 0x3F));
        }
        return chr(0xF0 | (($cp >> 18) & 0x07)) . chr(0x80 | (($cp >> 12) & 0x3F)) . chr(0x80 | (($cp >> 6) & 0x3F))
            . chr(0x80 | ($cp & 0x3F));
    }

    /**
     * The characters of $s, each as its bytes.
     *
     * @return list<string>
     */
    public static function split(string $s): array
    {
        $out = [];
        $length = strlen($s);
        for ($at = 0; $at < $length;) {
            $width = self::width(ord($s[$at]));
            $out[] = substr($s, $at, $width);
            $at += $width;
        }
        return $out;
    }

    /**
     * How many characters $s is, gone over a byte at a time: for the few bytes a table maps a
     * character to.
     */
    public static function countShort(string $s): int
    {
        $n = 0;
        $length = strlen($s);
        for ($at = 0; $at < $length; $at++) {
            if ((ord($s[$at]) & 0xC0) !== 0x80) {
                $n++;
            }
        }
        return $n;
    }
}
