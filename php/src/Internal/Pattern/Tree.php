<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * The parts of a pattern as a tree, each part a number and what it is held in lists by that
 * number: the shape a pattern is written in, before its anchors are placed, and the meaning it
 * comes to, which is the strings it accepts.
 *
 * No part holds another as a PHP value, and no part has an array of its own. A tree is as deep as
 * the text it was read from is nested, which is as deep as the text is long. PHP frees a value
 * that holds another by recursion in C, which no limit of PHP's own guards, so a tree of objects
 * tens of thousands of levels deep stops the process when it is let go of; and an array per part
 * takes several times the room of the numbers in it. Here a part is three numbers in lists that
 * grow together, the parts of every part are one flat list, and a set of symbols is held once
 * however many parts are over it.
 *
 * What a part is, by its kind:
 *
 *   - NOTHING is the one string of no symbols;
 *   - NEVER is no string at all, which is what an anchor nobody can satisfy leaves;
 *   - SYMBOLS is one symbol out of a set of them;
 *   - START and END are ^ and $ as written; a meaning holds neither, since whole-string matching
 *     settles what each comes to where the pattern is read;
 *   - IN_TURN is its parts one after another;
 *   - EITHER_OF is any one of its parts, two or more;
 *   - REPEATED is its one part between its least and its most times, the most NO_CEILING where
 *     nothing caps it.
 *
 * Whether a repetition is greedy or reluctant and whether a group captures say what an engine does
 * on the way and not which strings come out, so neither is here.
 *
 * @internal
 */
final class Tree
{
    public const NOTHING = 0;
    public const NEVER = 1;
    public const SYMBOLS = 2;
    public const START = 3;
    public const END = 4;
    public const IN_TURN = 5;
    public const EITHER_OF = 6;
    public const REPEATED = 7;

    /** What *, + and {n,} put where a repetition's most is: a bound nothing reaches rather than a large one. */
    public const NO_CEILING = -1;

    /** @var list<int> each part's kind */
    public array $kind = [];
    /**
     * @var list<int> for a part that has parts, where they begin in $list; for SYMBOLS, the number
     *      of its set in $sets; for REPEATED, also its least; nought otherwise
     */
    private array $first = [];
    /** @var list<int> how many parts a part has; for REPEATED, its most instead, its one part at $first */
    private array $count = [];
    /** @var array<int, int> each REPEATED part's least */
    private array $least = [];
    /** @var list<int> the parts of every part, one part's after another's */
    private array $list = [];
    /** @var list<list<int>> each set of symbols a part is over, once, as Symbols holds a set */
    public array $sets = [];
    /** @var array<string, int> each set's number, by its ranges written out */
    private array $setNumbers = [];

    /**
     * A part of $kind that holds nothing.
     */
    public function leaf(int $kind): int
    {
        return $this->add($kind, 0, 0);
    }

    /**
     * A SYMBOLS part over $held.
     *
     * @param list<int> $held
     */
    public function symbols(array $held): int
    {
        $key = implode(',', $held);
        $set = $this->setNumbers[$key] ?? null;
        if ($set === null) {
            $set = count($this->sets);
            $this->sets[] = $held;
            $this->setNumbers[$key] = $set;
        }
        return $this->add(self::SYMBOLS, $set, 0);
    }

    /**
     * An IN_TURN or EITHER_OF part of $parts.
     *
     * @param list<int> $parts
     */
    public function of(int $kind, array $parts): int
    {
        $first = count($this->list);
        array_push($this->list, ...$parts);
        return $this->add($kind, $first, count($parts));
    }

    /**
     * A REPEATED part.
     */
    public function repeated(int $what, int $least, int $most): int
    {
        $first = count($this->list);
        $this->list[] = $what;
        $part = $this->add(self::REPEATED, $first, $most);
        $this->least[$part] = $least;
        return $part;
    }

    private function add(int $kind, int $first, int $count): int
    {
        $this->kind[] = $kind;
        $this->first[] = $first;
        $this->count[] = $count;
        return count($this->kind) - 1;
    }

    /**
     * A copy into this tree of the leaf $part of $from.
     */
    public function copyLeaf(self $from, int $part): int
    {
        $kind = $from->kind[$part];
        return $kind === self::SYMBOLS ? $this->symbols($from->held($part)) : $this->leaf($kind);
    }

    /**
     * The parts of $part, none for a leaf.
     *
     * @return list<int>
     */
    public function partsOf(int $part): array
    {
        return match ($this->kind[$part]) {
            self::IN_TURN, self::EITHER_OF => array_slice($this->list, $this->first[$part], $this->count[$part]),
            self::REPEATED => [$this->list[$this->first[$part]]],
            default => [],
        };
    }

    /**
     * The one part a REPEATED part repeats.
     */
    public function repeats(int $part): int
    {
        return $this->list[$this->first[$part]];
    }

    /**
     * The fewest times a REPEATED part takes its part.
     */
    public function least(int $part): int
    {
        return $this->least[$part];
    }

    /**
     * The most times a REPEATED part takes its part, or NO_CEILING.
     */
    public function most(int $part): int
    {
        return $this->count[$part];
    }

    /**
     * The number in $sets of the set a SYMBOLS part is over.
     */
    public function setOf(int $part): int
    {
        return $this->first[$part];
    }

    /**
     * The set a SYMBOLS part is over.
     *
     * @return list<int>
     */
    public function held(int $part): array
    {
        return $this->sets[$this->first[$part]];
    }
}
