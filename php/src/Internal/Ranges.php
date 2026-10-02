<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

/**
 * Inclusive ranges of code points as the generated tables hold them: a flat list, each range its
 * first and its last code point, sorted and not overlapping.
 *
 * @internal
 */
final class Ranges
{
    /**
     * Whether $cp is in one of $ranges: the first range that ends at or after it, searched for,
     * holds it or nothing does.
     *
     * @param list<int> $ranges
     */
    public static function has(array $ranges, int $cp): bool
    {
        $low = 0;
        $high = intdiv(count($ranges), 2);
        while ($low < $high) {
            $mid = ($low + $high) >> 1;
            if ($ranges[2 * $mid + 1] < $cp) {
                $low = $mid + 1;
            } else {
                $high = $mid;
            }
        }
        return 2 * $low < count($ranges) && $ranges[2 * $low] <= $cp;
    }
}
