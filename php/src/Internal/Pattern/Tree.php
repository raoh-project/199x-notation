<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * The parts of a pattern as a tree, each part a number and what it is held in lists by that
 * number: the shape a pattern is written in, and once its anchors are placed (see Anchors), the
 * meaning it comes to, which is the strings it accepts. Placing the anchors changes each anchor
 * into what it comes to and nothing else, so the one tree is both.
 *
 * No part holds another as a PHP value, and no part has an array of its own. A tree is as deep as
 * the text it was read from is nested, which is as deep as the text is long. PHP frees a value
 * that holds another by recursion in C, which no limit of PHP's own guards, so a tree of objects
 * tens of thousands of levels deep stops the process when it is let go of; and an array per part
 * takes several times the room of the numbers in it. Here a part is three numbers in lists that
 * grow together, the parts of every part are one flat list, and a set of symbols is a number in
 * the SymbolSets the tree shares with the machine built from it.
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
    /** How many START and END parts the tree was written with, so that one with none is not walked for them. */
    public int $anchors = 0;
    /** @var array<int, int> each REPEATED part's least */
    private array $least = [];
    /** @var list<int> the parts of every part, one part's after another's */
    private array $list = [];

    public function __construct(public readonly SymbolSets $sets)
    {
    }

    /**
     * A part of $kind that holds nothing.
     */
    public function leaf(int $kind): int
    {
        if ($kind === self::START || $kind === self::END) {
            $this->anchors++;
        }
        return $this->add($kind, 0, 0);
    }

    /**
     * A SYMBOLS part over $held.
     *
     * @param list<int> $held
     */
    public function symbols(array $held): int
    {
        return $this->add(self::SYMBOLS, $this->sets->add($held), 0);
    }

    /**
     * An IN_TURN or EITHER_OF part of $items from $from on, which are taken off it: the parts of a
     * sequence or a choice being read go from the reader's list into the tree without being copied
     * into one of their own.
     *
     * @param list<int> $items
     */
    public function taken(int $kind, array &$items, int $from): int
    {
        $first = count($this->list);
        $count = count($items) - $from;
        for ($i = $from, $n = count($items); $i < $n; $i++) {
            $this->list[] = $items[$i];
        }
        if ($from === 0) {
            $items = [];
        } else {
            // Taken from the end, so that it costs what is taken and not the list: array_splice
            // would write the whole list out again, and the list is as long as the groups open
            // around the one being read.
            for ($i = 0; $i < $count; $i++) {
                array_pop($items);
            }
        }
        return $this->add($kind, $first, $count);
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
     * Makes the anchor $part the NOTHING or the NEVER it comes to, which is all placing the anchors
     * changes in a tree.
     */
    public function settle(int $part, int $kind): void
    {
        if (($this->kind[$part] !== self::START && $this->kind[$part] !== self::END)
            || ($kind !== self::NOTHING && $kind !== self::NEVER)) {
            throw new \LogicException('only an anchor is settled, and as nothing or never');
        }
        $this->kind[$part] = $kind;
    }

    /**
     * How many parts $part has, none for a leaf. Its parts are read one at a time by partAt and
     * never as a list of their own: a sequence may be as long as the text, and a copy of its parts
     * for each reading of them would take as much room as the tree.
     */
    public function partCount(int $part): int
    {
        return match ($this->kind[$part]) {
            self::IN_TURN, self::EITHER_OF => $this->count[$part],
            self::REPEATED => 1,
            default => 0,
        };
    }

    /**
     * The part at $i of the parts of $part.
     */
    public function partAt(int $part, int $i): int
    {
        return $this->list[$this->first[$part] + $i];
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
}
