<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * A pattern as its reader holds it before the anchors are placed. The one shape here a meaning
 * does not have is the anchor, whose answer is settled by where it stands among the rest of the
 * pattern, which is not known until the whole of it is read. Everything else is already its
 * meaning.
 *
 * @internal
 */
final class Written
{
    public const MEANT = 0;
    public const ANCHOR = 1;
    public const IN_TURN = 2;
    public const EITHER_OF = 3;
    public const REPEATED = 4;

    /**
     * @param self::MEANT|self::ANCHOR|self::IN_TURN|self::EITHER_OF|self::REPEATED $kind
     * @param Meaning|null $meaning what a MEANT means
     * @param bool         $end     whether an ANCHOR is $ rather than ^
     * @param list<self>   $parts   as a meaning's are
     */
    public function __construct(
        public readonly int $kind,
        public readonly ?Meaning $meaning = null,
        public readonly bool $end = false,
        public readonly array $parts = [],
        public readonly int $least = 0,
        public readonly int $most = 0,
    ) {
    }

    public static function meant(Meaning $meaning): self
    {
        return new self(self::MEANT, meaning: $meaning);
    }

    /**
     * What a MEANT means, which every one has.
     */
    public function meaning(): Meaning
    {
        return $this->meaning ?? throw new \LogicException('a written part that means nothing of its own');
    }
}
