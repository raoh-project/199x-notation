<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * What a pattern means: the set of strings it accepts, as regular-language operations over sets of
 * scalar values. The one form a pattern takes past its reader; the machine is built from it and
 * never from the text.
 *
 * What is kept is what the language depends on and nothing else. One written character is one
 * symbol, a set of one, and a literal, a class, a negated class, a shorthand and . all arrive as
 * symbols, told apart only by the set. An anchor is gone: whole-string matching settles what each
 * comes to where the pattern is read. Whether a repetition is greedy or reluctant and whether a
 * group captures say what an engine does on the way and not which strings come out, so none of
 * them is here either.
 *
 * @internal
 */
final class Meaning
{
    /** The one string of no symbols. */
    public const NOTHING = 0;
    /** No string at all, which is what an anchor nobody can satisfy leaves. */
    public const NEVER = 1;
    /** One symbol out of a set of them. */
    public const SYMBOLS = 2;
    /** Its parts one after another. */
    public const IN_TURN = 3;
    /** Any one of its arms, every arm and not the first. */
    public const EITHER_OF = 4;
    /** The same thing some number of times over, both ends carried. */
    public const REPEATED = 5;

    /** What *, + and {n,} put where a repetition's most is: a bound nothing reaches rather than a large one. */
    public const NO_CEILING = -1;

    private static ?self $nothing = null;

    /**
     * @param self::NOTHING|self::NEVER|self::SYMBOLS|self::IN_TURN|self::EITHER_OF|self::REPEATED $kind
     * @param list<int>  $held  the set of a SYMBOLS meaning
     * @param list<self> $parts what an IN_TURN meaning has one after another, the arms of an
     *                          EITHER_OF, two or more, and what a REPEATED repeats, alone
     * @param int        $least the fewest times of a REPEATED
     * @param int        $most  the most times of a REPEATED, or NO_CEILING
     */
    public function __construct(
        public readonly int $kind,
        public readonly array $held = [],
        public readonly array $parts = [],
        public readonly int $least = 0,
        public readonly int $most = 0,
    ) {
    }

    public static function nothing(): self
    {
        return self::$nothing ??= new self(self::NOTHING);
    }

    /**
     * @param list<int> $held
     */
    public static function symbols(array $held): self
    {
        return new self(self::SYMBOLS, held: $held);
    }
}
