<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

use Raoh\Notation199x\PatternLimit;

/**
 * The states a pattern comes to with its repetitions written out, counted from what is written
 * and without building anything. This is the measure PatternLimit::MachineStates is stated in,
 * and it is the specifications' and not this implementation's: every implementation counts the
 * same number from the same text. Counted on the pattern as written:
 *
 *   - a character, an escape, ., a shorthand and a class are one each; so are ^ and $;
 *   - an empty pattern, group or alternative is none;
 *   - a sequence is the sum of its parts, and a group is what is inside it;
 *   - a choice of n alternatives is one more than the sum of one more than each alternative;
 *   - A{n,m} is m times A, plus one; A{n} is A{n,n} and A? is A{0,1};
 *   - A{n,} is n + 1 times A, plus one; A* is A{0,} and A+ is A{1,};
 *   - the pattern is one more than what it is written as.
 *
 * For a pattern without anchors this is the states of the machine its shape builds, and an
 * anchor counts one and comes to at most one state, so a pattern within the limit always has a
 * machine. Counted up to one past the limit and no further, so a count as large as the reader
 * reads is multiplied without overflowing.
 *
 * Asked only of a pattern within the nesting depth, so the walk recurses.
 *
 * @internal
 */
final class States
{
    /**
     * The states the part $root of $written comes to as a whole pattern, or one past the limit
     * where it is past it.
     */
    public static function written(Tree $written, int $root): int
    {
        return self::plus(1, self::in($written, $root));
    }

    private static function in(Tree $written, int $part): int
    {
        $count = $written->partCount($part);
        switch ($written->kind[$part]) {
            case Tree::NOTHING:
                return 0;
            case Tree::IN_TURN:
                $sum = 0;
                for ($i = 0; $i < $count; $i++) {
                    $sum = self::plus($sum, self::in($written, $written->partAt($part, $i)));
                }
                return $sum;
            case Tree::EITHER_OF:
                $sum = 1;
                for ($i = 0; $i < $count; $i++) {
                    $sum = self::plus($sum, self::plus(1, self::in($written, $written->partAt($part, $i))));
                }
                return $sum;
            case Tree::REPEATED:
                // Its copies, and the state it ends in.
                $most = $written->most($part);
                $copies = $most === Tree::NO_CEILING ? $written->least($part) + 1 : $most;
                return self::plus(self::times($copies, self::in($written, $written->repeats($part))), 1);
            default:
                // A set of symbols, or an anchor.
                return 1;
        }
    }

    private static function past(): int
    {
        return PatternLimit::MachineStates->most() + 1;
    }

    private static function plus(int $one, int $other): int
    {
        return min(self::past(), $one + $other);
    }

    private static function times(int $copies, int $body): int
    {
        if ($copies === 0 || $body === 0) {
            return 0;
        }
        if ($copies > intdiv(self::past(), $body)) {
            return self::past();
        }
        return min(self::past(), $copies * $body);
    }
}
