<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * A temporal that has a text form.
 */
enum TemporalKind
{
    /** A day: yyyy-MM-dd. */
    case Date;
    /** A time of day: HH:mm or HH:mm:ss, with a fraction of a second or not. */
    case Time;
    /** A day and a time of day, joined by T. */
    case DateTime;
    /** A day, a time of day, and an offset from UTC: Z or ±HH:mm[:ss]. */
    case OffsetDateTime;
    /** A moment: a day, a time of day with its seconds, and an offset from UTC. */
    case Instant;
}
