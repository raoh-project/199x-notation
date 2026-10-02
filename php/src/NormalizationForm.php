<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * One of the four normalization forms of UAX #15.
 */
enum NormalizationForm
{
    /** Canonical decomposition, then canonical composition. */
    case NFC;
    /** Canonical decomposition. */
    case NFD;
    /** Compatibility decomposition, then canonical composition. */
    case NFKC;
    /** Compatibility decomposition. */
    case NFKD;
}
