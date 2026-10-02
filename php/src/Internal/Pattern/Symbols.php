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

    /** @var array<string, list<int>> */
    private static array $named = [];

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

    /**
     * What a pattern's . stands for: every symbol but the line terminators, a line feed, a
     * carriage return, the next-line character and the two separators. Written as a difference,
     * so that a negated class, which does not leave them out, is the same algebra with a different
     * set taken away.
     *
     * @return list<int>
     */
    public static function dot(): array
    {
        return self::$named['dot'] ??= self::less(
            self::not([]),
            self::union(self::one(0x0A), self::one(0x0D), self::one(0x85), self::one(0x2028), self::one(0x2029)),
        );
    }

    /**
     * A pattern's \d: the ten ASCII digits and no other.
     *
     * @return list<int>
     */
    public static function digit(): array
    {
        return self::between(0x30, 0x39);
    }

    /**
     * A pattern's \w: the ASCII letters, the ASCII digits and the underscore.
     *
     * @return list<int>
     */
    public static function word(): array
    {
        return self::$named['word'] ??= self::union(
            self::between(0x61, 0x7A),
            self::between(0x41, 0x5A),
            self::digit(),
            self::one(0x5F),
        );
    }

    /**
     * A pattern's \s: a space, a tab, a line feed, a vertical tab, a form feed and a carriage
     * return. Not the White_Space set, which is a separate set the specifications state separately.
     *
     * @return list<int>
     */
    public static function space(): array
    {
        return self::$named['space'] ??= self::union(self::one(0x20), self::between(0x09, 0x0D));
    }

    /**
     * The complement of a shorthand, worked out once rather than at each place a pattern writes it.
     *
     * @param 'digit'|'word'|'space' $shorthand
     * @return list<int>
     */
    public static function notOf(string $shorthand): array
    {
        return self::$named['not ' . $shorthand] ??= self::not(match ($shorthand) {
            'digit' => self::digit(),
            'word' => self::word(),
            'space' => self::space(),
        });
    }
}
