<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\WhiteSpaceTables;

/**
 * The Unicode White_Space set, as of Unicode 18.0.0.
 */
final class WhiteSpace
{
    private function __construct()
    {
    }

    /**
     * Whether the code point $codePoint has the White_Space property, as of Unicode 18.0.0:
     * twenty-five code points.
     *
     * The set is generated from the database's PropList.txt rather than read off PCRE's \s or
     * IntlChar::isUWhiteSpace, which answer for the Unicode version they were built with.
     */
    public static function contains(int $codePoint): bool
    {
        // A handful of ranges, so a scan in order is as quick as a search.
        $ranges = WhiteSpaceTables::WHITE_SPACE;
        for ($i = 0, $n = count($ranges); $i < $n; $i += 2) {
            if ($codePoint < $ranges[$i]) {
                return false;
            }
            if ($codePoint <= $ranges[$i + 1]) {
                return true;
            }
        }
        return false;
    }
}
