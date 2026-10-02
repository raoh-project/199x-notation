<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * The sets of symbols a pattern's parts are over, each by its number: every set's ranges one after
 * another in one flat list, and where each set's begin (the layout called compressed sparse rows).
 *
 * A pattern may write as many different sets as it writes characters, two hundred thousand of them
 * within the limit of states, so no set is an array of its own: an array per set takes several
 * times the room of the numbers in it, and would make how many different characters a pattern
 * writes a limit PHP has and the specifications do not. The tree and the machine built from it
 * name a set by the same number here, so building the machine copies no set.
 *
 * A set of one code point, and each set the language names (., \d, \w, \s and their complements),
 * is held once however often it is written, by a key that is the set and nothing less: the code
 * point, or which named set it is. Any other set, which only a class writes, is held as it is
 * written. Its ranges cost the text of the class that wrote them, so what they take grows with the
 * text as the text itself does, and telling two classes alike would take a key made from their
 * ranges: a string as large again as the sets, or a checksum, under which sets that differ share a
 * key, and text written to make many of them share one makes every set added search through them.
 *
 * The table of the sets held once is only for reading: seal() lets go of it, and what a pattern
 * keeps holds the ranges and no table.
 *
 * @internal
 */
final class SymbolSets
{
    /** The sets the language names, each a key below every code point. */
    private const NAMED = [
        -1 => Symbols::DOT,
        -2 => Symbols::DIGIT,
        -3 => Symbols::WORD,
        -4 => Symbols::SPACE,
        -5 => Symbols::NOT_DIGIT,
        -6 => Symbols::NOT_WORD,
        -7 => Symbols::NOT_SPACE,
    ];

    /** @var list<int> where each set's ranges begin in $ranges, and one more where the last one's end */
    public array $start = [0];
    /** @var list<int> every set's ranges, each its first and its last code point */
    public array $ranges = [];
    /**
     * @var array<int, int>|null the number of each set held once, by its code point or by which
     *      named set it is; null once sealed
     */
    private ?array $seen = [];

    /**
     * The number of the set $held, as Symbols holds a set: the one it already has where $held is a
     * set held once and is here, and otherwise a new one.
     *
     * @param list<int> $held
     */
    public function add(array $held): int
    {
        // The table is read through the property and not a copy of it in a variable: written to
        // while a variable held it, PHP would copy the whole table on each set added.
        if ($this->seen === null) {
            throw new \LogicException('a set added once the sets were sealed');
        }
        $key = self::keyOf($held);
        if ($key !== null && isset($this->seen[$key])) {
            return $this->seen[$key];
        }
        $set = count($this->start) - 1;
        foreach ($held as $bound) {
            $this->ranges[] = $bound;
        }
        $this->start[] = count($this->ranges);
        if ($key !== null) {
            $this->seen[$key] = $set;
        }
        return $set;
    }

    /**
     * The key $held is held once by, which is $held and nothing less, or null where it is held as
     * written.
     *
     * @param list<int> $held
     */
    private static function keyOf(array $held): ?int
    {
        if (count($held) === 2 && $held[0] === $held[1]) {
            return $held[0];
        }
        // A named set is asked of only where it has as many bounds, which most classes have not.
        $count = count($held);
        foreach (self::NAMED as $key => $named) {
            if (count($named) === $count && $held === $named) {
                return $key;
            }
        }
        return null;
    }

    /**
     * Lets go of what only adding sets needs. No set is added afterwards.
     */
    public function seal(): void
    {
        $this->seen = null;
    }

    /**
     * The set numbered $set, as Symbols holds a set.
     *
     * @return list<int>
     */
    public function held(int $set): array
    {
        return array_slice($this->ranges, $this->start[$set], $this->start[$set + 1] - $this->start[$set]);
    }

    /**
     * Whether the set numbered $set holds $cp: the first of its ranges that ends at or after $cp,
     * searched for, holds it or nothing does.
     */
    public function holds(int $set, int $cp): bool
    {
        $from = $this->start[$set];
        $low = 0;
        $high = ($this->start[$set + 1] - $from) >> 1;
        while ($low < $high) {
            $mid = ($low + $high) >> 1;
            if ($this->ranges[$from + 2 * $mid + 1] < $cp) {
                $low = $mid + 1;
            } else {
                $high = $mid;
            }
        }
        $at = $from + 2 * $low;
        return $at < $this->start[$set + 1] && $this->ranges[$at] <= $cp;
    }
}
