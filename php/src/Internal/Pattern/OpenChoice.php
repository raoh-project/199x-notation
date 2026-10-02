<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * A choice being read, in a group or at the top: the arms read so far, and the parts of the one
 * being read.
 *
 * @internal
 */
final class OpenChoice
{
    /** @var list<Written> */
    public array $arms = [];
    /** @var list<Written> */
    public array $parts = [];

    public function __construct(private readonly Nodes $nodes)
    {
    }

    /**
     * The arm being read: an arm of one part is that part, and an arm of none is nothing.
     */
    public function arm(): Written
    {
        return match (count($this->parts)) {
            0 => Written::meant(Meaning::nothing()),
            1 => $this->parts[0],
            default => $this->nodes->held(new Written(Written::IN_TURN, parts: $this->parts)),
        };
    }

    /**
     * The choice: a choice of one arm is that arm.
     */
    public function choice(): Written
    {
        $this->arms[] = $this->arm();
        if (count($this->arms) === 1) {
            return $this->arms[0];
        }
        return $this->nodes->held(new Written(Written::EITHER_OF, parts: $this->arms));
    }

    /**
     * Puts $w at the end of the arm being read. A group of nothing is nothing, and is left out so
     * that one written pattern has one tree. An anchor is not one of those: where it stands decides
     * what it comes to.
     */
    public function part(Written $w): void
    {
        if ($w->kind === Written::MEANT && $w->meaning()->kind === Meaning::NOTHING) {
            return;
        }
        $this->parts[] = $w;
    }
}
