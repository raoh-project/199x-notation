<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * The parts of a tree the reader built, each held here as well as by its parent, so that the tree
 * can be taken apart without recursion.
 *
 * The reader reads text nested as deeply as it is long, and builds a tree as deep. PHP frees an
 * object that holds another by recursion in C, which no limit of PHP's own guards, so a tree a few
 * tens of thousands of levels deep stops the process when the last reference to its root goes.
 * Emptying each part's parts from this flat list first leaves nothing nested to free.
 *
 * @internal
 */
final class Nodes
{
    /** @var list<Written|Meaning> */
    private array $made = [];

    /**
     * $node, held to be taken apart.
     *
     * @template T of Written|Meaning
     * @param T $node
     * @return T
     */
    public function held(Written|Meaning $node): Written|Meaning
    {
        $this->made[] = $node;
        return $node;
    }

    /**
     * Takes apart every part held: none of them holds another afterwards.
     */
    public function release(): void
    {
        foreach ($this->made as $node) {
            $node->parts = [];
        }
        $this->made = [];
    }
}
