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
 * A set written more than once is held once, by a table of the sets seen so far that is only for
 * reading: seal() lets go of it, and what a pattern keeps holds the ranges and no table.
 *
 * @internal
 */
final class SymbolSets
{
    /** @var list<int> where each set's ranges begin in $ranges, and one more where the last one's end */
    public array $start = [0];
    /** @var list<int> every set's ranges, each its first and its last code point */
    public array $ranges = [];
    /**
     * @var array<int, int>|null each set's number while sets are being added, by an integer: a set
     *      of one code point by the code point, any other by a checksum of its ranges above every
     *      code point; null once sealed. A number and not the ranges written out, which would take
     *      as much room again as the sets.
     */
    private ?array $seen = [];

    /**
     * The number of the set $held, as Symbols holds a set, added where it is not here yet.
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
        $key = count($held) === 2 && $held[0] === $held[1] ? $held[0] : 0x200000 + crc32(implode(',', $held));
        $seen = $this->seen[$key] ?? null;
        // A checksum two sets share is asked of the ranges, and a set that differs is held apart:
        // holding a set once is what saves room, and holding it twice changes no answer.
        if ($seen !== null && $this->held($seen) === $held) {
            return $seen;
        }
        $set = count($this->start) - 1;
        foreach ($held as $bound) {
            $this->ranges[] = $bound;
        }
        $this->start[] = count($this->ranges);
        $this->seen[$key] ??= $set;
        return $set;
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
