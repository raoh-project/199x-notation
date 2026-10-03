<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * Builds the states and steps of a machine from a meaning, laid out as the rows Machine holds: an
 * offset for each state and the steps from it one after another, in the order they were made.
 *
 * A step is made where it comes from, which may be a state made long before, so the steps are
 * written as they come; and then, the steps from each state counted, each is moved, in the lists
 * it was written in, to its place in its state's row, where they were not made state by state. What is held besides the rows is the state
 * each step came from, one number a step, which becomes the place it goes and is let go of once
 * the steps are in place: laying the steps out into lists of their own would hold them twice over.
 *
 * @internal
 */
final class MachineBuilder
{
    public int $states = 0;
    /** @var array<int, int> once built, where each state's steps begin in $stepTo, and one more where the last one's end */
    public array $stepStart = [];
    /** @var array<int, int> */
    public array $stepTo = [];
    /** @var array<int, int> */
    public array $stepOver = [];
    /** @var array<int, int> as $stepStart, for the steps for nothing */
    public array $freeStart = [];
    /** @var array<int, int> */
    public array $freeTo = [];
    /** @var array<int, int> the state each step comes from, in the order they were made */
    private array $stepFrom = [];
    /** @var array<int, int> */
    private array $freeFrom = [];

    public function __construct(private readonly Tree $tree)
    {
    }

    /**
     * Builds the machine for $root, and answers the state it accepts in.
     */
    public function build(int $root): int
    {
        $accept = $this->part($root, $this->state());
        $this->stepStart = self::laidOut($this->states, $this->stepFrom, $this->stepTo, $this->stepOver);
        $empty = [];
        $this->freeStart = self::laidOut($this->states, $this->freeFrom, $this->freeTo, $empty);
        return $accept;
    }

    /**
     * Moves the steps in $to, and in $over where it is not empty, each to its place in the row of
     * the state $from says it comes from, keeping the order they were made in within a row, and
     * answers where each of the $states rows begins, with one more where the last one ends. $from
     * is used up.
     *
     * Each step's place is worked out first, into $from, and the steps are then moved round the
     * cycles those places make: a step moved to its place is never moved again, so moving them all
     * is as many moves as there are steps, and takes no list but those they are in.
     *
     * @param array<int, int> $from
     * @param array<int, int> $to
     * @param array<int, int> $over
     * @return array<int, int>
     */
    private static function laidOut(int $states, array &$from, array &$to, array &$over): array
    {
        $start = array_fill(0, $states + 1, 0);
        $inOrder = true;
        $last = 0;
        foreach ($from as $q) {
            $start[$q + 1]++;
            $inOrder = $inOrder && $q >= $last;
            $last = $q;
        }
        for ($q = 0; $q < $states; $q++) {
            $start[$q + 1] += $start[$q];
        }
        // Steps made state by state, as a sequence or a repetition of one makes them, are in their
        // rows already, and are left where they are.
        if ($inOrder) {
            $from = [];
            return $start;
        }
        $sum = $start[$states];
        for ($i = 0; $i < $sum; $i++) {
            $from[$i] = $start[$from[$i]]++;
        }
        // Each row's next place is now where the following row begins.
        for ($q = $states - 1; $q > 0; $q--) {
            $start[$q] = $start[$q - 1];
        }
        $start[0] = 0;
        $moving = $over !== [];
        for ($i = 0; $i < $sum; $i++) {
            while (($place = $from[$i]) !== $i) {
                $held = $to[$i];
                $to[$i] = $to[$place];
                $to[$place] = $held;
                if ($moving) {
                    $held = $over[$i];
                    $over[$i] = $over[$place];
                    $over[$place] = $held;
                }
                $from[$i] = $from[$place];
                $from[$place] = $place;
            }
        }
        $from = [];
        return $start;
    }

    public function state(): int
    {
        return $this->states++;
    }

    private function freely(int $from, int $to): void
    {
        $this->freeFrom[] = $from;
        $this->freeTo[] = $to;
    }

    /**
     * Makes the states for the part $part, walked into from $from, and answers where it leaves
     * off: one entry and one exit apiece, which is what lets the shapes compose without any of
     * them knowing what it is inside. Recursive, since a pattern that was read nests no deeper
     * than the nesting depth.
     */
    public function part(int $part, int $from): int
    {
        $tree = $this->tree;
        switch ($tree->kind[$part]) {
            case Tree::NOTHING:
                return $from;
            case Tree::NEVER:
                // Nothing leads out of it, so nothing after it is reached.
                return $this->state();
            case Tree::SYMBOLS:
                // A step is over its set by the set's number in the tree, which holds each set once.
                $to = $this->state();
                $this->stepFrom[] = $from;
                $this->stepTo[] = $to;
                $this->stepOver[] = $tree->setOf($part);
                return $to;
            case Tree::IN_TURN:
                $at = $from;
                for ($i = 0, $n = $tree->partCount($part); $i < $n; $i++) {
                    $at = $this->part($tree->partAt($part, $i), $at);
                }
                return $at;
            case Tree::EITHER_OF:
                $out = $this->state();
                for ($i = 0, $n = $tree->partCount($part); $i < $n; $i++) {
                    $in = $this->state();
                    $this->freely($from, $in);
                    $this->freely($this->part($tree->partAt($part, $i), $in), $out);
                }
                return $out;
            default:
                return $this->repeated($part, $from);
        }
    }

    /**
     * Makes a repetition as the copies it is: the floor is copies one after another, what is above
     * it is copies each of which may be stepped over, and an unbounded ceiling is one more copy
     * with a step back to where it began.
     */
    private function repeated(int $part, int $from): int
    {
        $tree = $this->tree;
        $what = $tree->repeats($part);
        $least = $tree->least($part);
        $most = $tree->most($part);
        // A body that makes no state is the empty string however many times it is taken, and is
        // built as that: one state to end in. Copied a count at a time it would cost the count and
        // make nothing.
        if ($this->buildsNoState($what)) {
            $out = $this->state();
            $this->freely($from, $out);
            return $out;
        }
        $at = $from;
        for ($i = 0; $i < $least; $i++) {
            $at = $this->part($what, $at);
        }
        if ($most === Tree::NO_CEILING) {
            $loop = $this->state();
            $this->freely($at, $loop);
            $this->freely($this->part($what, $loop), $loop);
            return $loop;
        }
        $out = $this->state();
        $this->freely($at, $out);
        for ($i = $least; $i < $most; $i++) {
            $at = $this->part($what, $at);
            $this->freely($at, $out);
        }
        return $out;
    }

    /**
     * Whether building $part makes no state, which is only ever the empty string.
     */
    private function buildsNoState(int $part): bool
    {
        $kind = $this->tree->kind[$part];
        if ($kind === Tree::NOTHING) {
            return true;
        }
        if ($kind !== Tree::IN_TURN) {
            return false;
        }
        for ($i = 0, $n = $this->tree->partCount($part); $i < $n; $i++) {
            if (!$this->buildsNoState($this->tree->partAt($part, $i))) {
                return false;
            }
        }
        return true;
    }
}
