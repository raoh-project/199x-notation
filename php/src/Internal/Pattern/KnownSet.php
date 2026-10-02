<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * A set of states a walk has been in, and where the characters it has read from it lead: the sets
 * of states a deterministic machine would have, made only as a walk comes to them.
 *
 * A set leads to another by reference and not by a place in a list, so a set that is forgotten is
 * still the set it was to whatever holds it: nothing can be held that names a different set.
 *
 * @internal
 */
final class KnownSet
{
    /** @var array<string, KnownSet> where each character read from here leads, by its UTF-8 bytes */
    public array $next = [];

    /**
     * @param list<int> $states the states of the set, ascending, every step for nothing already taken
     * @param bool      $none   whether the set has no state, so that no string from here on is accepted
     */
    public function __construct(
        public readonly array $states,
        public readonly bool $accepts,
        public readonly bool $none,
    ) {
    }
}
