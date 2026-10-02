<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * What TemporalText::check answers: that a text is admitted, or why it is not. The refusals are
 * reasons and not a flag, because they are different things to tell a caller: text that is no
 * temporal, and text that names a leap second.
 */
enum TemporalAnswer
{
    /** Text that is a temporal of the kind asked about. */
    case Admitted;
    /** Text that is not in the form, or is in it and names no day or moment. */
    case Malformed;
    /** An instant whose second is 60. */
    case LeapSecond;
}
