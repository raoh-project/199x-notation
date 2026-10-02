<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * Text that is no pattern of the language, and what stopped the reading.
 */
final readonly class PatternRefused
{
    /**
     * @param PatternRefusal $why       which kind of thing it is
     * @param int            $from      where in the text the construct that stopped the reading
     *                                  begins, in bytes
     * @param string         $construct the construct as written, which is empty where the text
     *                                  ended before a construct it had begun was whole
     */
    public function __construct(
        public PatternRefusal $why,
        public int $from,
        public string $construct,
    ) {
    }
}
