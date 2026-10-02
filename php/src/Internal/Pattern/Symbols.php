<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * Sets of the characters a string is made of: Unicode scalar values, every code point but the
 * surrogates.
 *
 * The universe is what text can hold. No text holds a surrogate, so no set here names one: a range
 * never holds one, and the complement is taken within the scalar values. That is what lets every
 * sequence of symbols a machine reads be a string.
 *
 * A set is a flat list of ranges, each its first and its last code point, sorted, apart and never
 * touching, so that one set has one spelling. A literal is one code point, a class is a union of
 * ranges, a negated class is the universe less that union, and . is the universe less the five
 * line terminators: the same algebra, so nothing downstream needs to know which shape a set came
 * from.
 *
 * @internal
 */
final class Symbols
{
    public const LAST = 0x10FFFF;
    public const SURROGATES_FROM = 0xD800;
    public const SURROGATES_TO = 0xDFFF;

    public static function isSurrogate(int $cp): bool
    {
        return $cp >= self::SURROGATES_FROM && $cp <= self::SURROGATES_TO;
    }

    /**
     * The set of $cp alone, a scalar value.
     *
     * @return list<int>
     */
    public static function one(int $cp): array
    {
        return [$cp, $cp];
    }

    /**
     * Every scalar value from $first to $last, both ends in it, the surrogates left out. Both ends
     * are scalar values, and $first is not above $last.
     *
     * @return list<int>
     */
    public static function between(int $first, int $last): array
    {
        return self::normalized(self::scalarsIn($first, $last, []));
    }

    /**
     * $out with the runs the scalar values in $first..$last make, none, one or two of them.
     *
     * @param list<int> $out
     * @return list<int>
     */
    private static function scalarsIn(int $first, int $last, array $out): array
    {
        if ($first < self::SURROGATES_FROM) {
            array_push($out, $first, min($last, self::SURROGATES_FROM - 1));
        }
        if ($last > self::SURROGATES_TO) {
            array_push($out, max($first, self::SURROGATES_TO + 1), $last);
        }
        return $out;
    }

    /**
     * The symbols in any of $sets.
     *
     * @param list<int> ...$sets
     * @return list<int>
     */
    public static function union(array ...$sets): array
    {
        return self::normalized(array_merge(...$sets));
    }

    /**
     * The runs of $given, in any order, sorted and joined where they touch or overlap, so that one
     * set has one spelling.
     *
     * @param list<int> $given
     * @return list<int>
     */
    public static function normalized(array $given): array
    {
        $runs = [];
        for ($i = 0, $n = count($given); $i < $n; $i += 2) {
            $runs[] = [$given[$i], $given[$i + 1]];
        }
        usort($runs, static fn (array $a, array $b): int => [$a[0], $a[1]] <=> [$b[0], $b[1]]);
        $out = [];
        // The run being joined, which is written out once a run apart from it comes.
        $joining = null;
        foreach ($runs as [$first, $last]) {
            if ($joining !== null && $first <= $joining[1] + 1) {
                $joining[1] = max($joining[1], $last);
                continue;
            }
            if ($joining !== null) {
                array_push($out, ...$joining);
            }
            $joining = [$first, $last];
        }
        if ($joining !== null) {
            array_push($out, ...$joining);
        }
        return $out;
    }

    /**
     * Every scalar value $set does not hold.
     *
     * @param list<int> $set
     * @return list<int>
     */
    public static function not(array $set): array
    {
        $out = [];
        $next = 0;
        for ($i = 0, $n = count($set); $i < $n; $i += 2) {
            if ($set[$i] > $next) {
                $out = self::scalarsIn($next, $set[$i] - 1, $out);
            }
            $next = $set[$i + 1] + 1;
        }
        if ($next <= self::LAST) {
            $out = self::scalarsIn($next, self::LAST, $out);
        }
        return self::normalized($out);
    }

    /**
     * $set without $those.
     *
     * @param list<int> $set
     * @param list<int> $those
     * @return list<int>
     */
    public static function less(array $set, array $those): array
    {
        return self::not(self::union(self::not($set), $those));
    }

    /**
     * How many symbols $set holds.
     *
     * @param list<int> $set
     */
    public static function size(array $set): int
    {
        $n = 0;
        for ($i = 0, $count = count($set); $i < $count; $i += 2) {
            $n += $set[$i + 1] - $set[$i] + 1;
        }
        return $n;
    }

    // The sets the language names, written out: each is fixed by the specifications, and a test
    // holds each to the algebra that states it, so that no request works them out again.

    /** What a pattern's . stands for: every symbol but the line terminators, a line feed, a carriage return, the next-line character and the two separators. */
    public const DOT = [0x0000, 0x0009, 0x000B, 0x000C, 0x000E, 0x0084, 0x0086, 0x2027, 0x202A, 0xD7FF, 0xE000, 0x10FFFF];
    /** A pattern's \d: the ten ASCII digits and no other. */
    public const DIGIT = [0x0030, 0x0039];
    /** A pattern's \w: the ASCII letters, the ASCII digits and the underscore. */
    public const WORD = [0x0030, 0x0039, 0x0041, 0x005A, 0x005F, 0x005F, 0x0061, 0x007A];
    /** A pattern's \s: a space, a tab, a line feed, a vertical tab, a form feed and a carriage return. Not the White_Space set, which the specifications state separately. */
    public const SPACE = [0x0009, 0x000D, 0x0020, 0x0020];
    /** A pattern's \D: every symbol DIGIT does not hold. */
    public const NOT_DIGIT = [0x0000, 0x002F, 0x003A, 0xD7FF, 0xE000, 0x10FFFF];
    /** A pattern's \W: every symbol WORD does not hold. */
    public const NOT_WORD = [0x0000, 0x002F, 0x003A, 0x0040, 0x005B, 0x005E, 0x0060, 0x0060, 0x007B, 0xD7FF, 0xE000, 0x10FFFF];
    /** A pattern's \S: every symbol SPACE does not hold. */
    public const NOT_SPACE = [0x0000, 0x0008, 0x000E, 0x001F, 0x0021, 0xD7FF, 0xE000, 0x10FFFF];
}
