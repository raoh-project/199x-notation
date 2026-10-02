<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * A choice being read, in a group or at the top: the arms read so far, and the parts of the one
 * being read, as parts of the written tree.
 *
 * @internal
 */
final class OpenChoice
{
    /** @var list<int> */
    public array $arms = [];
    /** @var list<int> */
    public array $parts = [];

    public function __construct(private readonly Tree $written)
    {
    }

    /**
     * The arm being read: an arm of one part is that part, and an arm of none is nothing.
     */
    public function arm(): int
    {
        return match (count($this->parts)) {
            0 => $this->written->leaf(Tree::NOTHING),
            1 => $this->parts[0],
            default => $this->written->of(Tree::IN_TURN, $this->parts),
        };
    }

    /**
     * The choice: a choice of one arm is that arm.
     */
    public function choice(): int
    {
        $this->arms[] = $this->arm();
        if (count($this->arms) === 1) {
            return $this->arms[0];
        }
        return $this->written->of(Tree::EITHER_OF, $this->arms);
    }

    /**
     * Puts $part at the end of the arm being read. A group of nothing is nothing, and is left out
     * so that one written pattern has one tree. An anchor is not one of those: where it stands
     * decides what it comes to.
     */
    public function part(int $part): void
    {
        if ($this->written->kind[$part] === Tree::NOTHING) {
            return;
        }
        $this->parts[] = $part;
    }
}
