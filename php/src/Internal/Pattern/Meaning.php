<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * What a pattern means: the set of strings it accepts, as regular-language operations over sets of
 * scalar values, and the part of the tree that is the whole of it. The one form a pattern takes
 * past its reader; the machine is built from it and never from the text.
 *
 * What is kept is what the language depends on and nothing else. One written character is one
 * symbol, a set of one, and a literal, a class, a negated class, a shorthand and . all arrive as
 * symbols, told apart only by the set. An anchor is gone (see Anchors).
 *
 * @internal
 */
final readonly class Meaning
{
    public function __construct(public Tree $tree, public int $root)
    {
    }
}
