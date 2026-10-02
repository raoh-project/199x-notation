<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * A pattern of the language written past one of the limits every implementation holds to.
 *
 * Not a refusal: the language has no count, depth or size past which a pattern stops being one.
 * What is past a limit is what no implementation is asked to run. Answered only of text read to
 * its end and found to be a pattern, its anchors placed: text that is no pattern is
 * PatternRefused, whatever limit it also went past.
 */
final readonly class PatternBeyond
{
    /**
     * @param PatternLimit $limit     which limit it is past
     * @param int          $from      where in the text the construct that is past it begins, in
     *                                bytes; nought for PatternLimit::MachineStates, which is a fact
     *                                about the whole pattern
     * @param string       $construct the construct as written: the count, the group opened past
     *                                the depth, or the whole pattern
     */
    public function __construct(
        public PatternLimit $limit,
        public int $from,
        public string $construct,
    ) {
    }
}
