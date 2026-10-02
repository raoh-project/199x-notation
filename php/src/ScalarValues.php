<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * Text as a sequence of Unicode scalar values: whether a string is one, its length and its order.
 *
 * A PHP string is bytes, and every rule here is stated of text that is a sequence of scalar
 * values, which a string is where it is valid UTF-8. invalidUtf8At is the question a caller asks
 * before it takes text in. The other functions of this package take text that is valid UTF-8 and
 * do not ask it, apart from Pattern::read, which refuses such text, and Pattern::matches, which
 * accepts none.
 */
final class ScalarValues
{
    private function __construct()
    {
    }

    /**
     * Where $s holds bytes that are not UTF-8, as the byte offset the first such sequence begins
     * at, or null where $s is valid UTF-8 and so is a sequence of scalar values.
     *
     * A surrogate written in UTF-8's form, such as the bytes ED A0 80, is not UTF-8: a surrogate is
     * half of a UTF-16 pair and no scalar value. So is a sequence longer than it has to be, and one
     * past U+10FFFF.
     */
    public static function invalidUtf8At(string $s): ?int
    {
        // Most text is valid, and PCRE's check of UTF-8, which is what the u modifier makes it do
        // before it matches, says so far faster than a scan in PHP. What is and is not UTF-8 does
        // not follow a Unicode version. Only text that is not is gone over again for the place.
        if (preg_match('//u', $s) === 1) {
            return null;
        }
        $length = strlen($s);
        for ($at = 0; $at < $length;) {
            [$cp, $width] = Internal\Utf8::scalarAt($s, $at);
            if ($cp < 0) {
                return $at;
            }
            $at += $width;
        }
        // PCRE and this scan disagree only if one of them is wrong.
        throw new \LogicException('PCRE refused text this scan reads as UTF-8');
    }

    /**
     * How many scalar values $s is made of, which is the length every rule here measures text in,
     * and not strlen($s), which counts bytes.
     *
     * $s is valid UTF-8 (see invalidUtf8At); this does not ask.
     */
    public static function count(string $s): int
    {
        // Every byte but a continuation byte begins a scalar value.
        $continuations = 0;
        foreach (count_chars($s, 1) as $byte => $times) {
            if ($byte >= 0x80 && $byte <= 0xBF) {
                $continuations += $times;
            }
        }
        return strlen($s) - $continuations;
    }

    /**
     * Where $a stands against $b: the first scalar value where they differ decides, and where one
     * is a prefix of the other the shorter is below. It answers -1, 0 or 1 as $a stands below, with
     * or above $b.
     *
     * The order of UTF-8 bytes is the order of the scalar values they encode, so on valid UTF-8
     * this is the order of the strings as strcmp compares them. $a and $b are valid UTF-8 (see
     * invalidUtf8At); this does not ask.
     */
    public static function compare(string $a, string $b): int
    {
        // strcmp and not <=>, which compares two numeric strings as numbers: "10" and "1e1" alike.
        return strcmp($a, $b) <=> 0;
    }
}
