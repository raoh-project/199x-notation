<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * Builds the states and steps of a machine from a meaning, as flat lists that Machine lays out as
 * rows once the whole of it is made. A step is made where it comes from, which may be a state made
 * long before, so the steps are gathered as they come and put in order after.
 *
 * @internal
 */
final class MachineBuilder
{
    public int $states = 0;
    /** @var list<int> */
    public array $stepFrom = [];
    /** @var list<int> */
    public array $stepTo = [];
    /** @var list<int> */
    public array $stepOver = [];
    /** @var list<int> */
    public array $freeFrom = [];
    /** @var list<int> */
    public array $freeTo = [];
    public function __construct(private readonly Tree $tree)
    {
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
                foreach ($tree->partsOf($part) as $each) {
                    $at = $this->part($each, $at);
                }
                return $at;
            case Tree::EITHER_OF:
                $out = $this->state();
                foreach ($tree->partsOf($part) as $arm) {
                    $in = $this->state();
                    $this->freely($from, $in);
                    $this->freely($this->part($arm, $in), $out);
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
        foreach ($this->tree->partsOf($part) as $each) {
            if (!$this->buildsNoState($each)) {
                return false;
            }
        }
        return true;
    }
}
