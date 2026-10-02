<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

use Raoh\Notation199x\PatternRefusal;

/**
 * What the reader throws where the text is no pattern, carried to the one place that answers.
 * Never seen outside the reader.
 *
 * @internal
 */
final class Refusal extends \Exception
{
    public function __construct(
        public readonly PatternRefusal $why,
        public readonly int $from,
        public readonly int $to,
    ) {
        parent::__construct($why->name);
    }
}
