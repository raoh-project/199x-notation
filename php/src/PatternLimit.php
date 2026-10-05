<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * One of the limits on a pattern every implementation holds to, each the same number everywhere.
 * They bound what running a pattern costs, and are stated of the text so that no implementation's
 * way of running one decides which patterns it takes.
 */
enum PatternLimit
{
    /** A count of a repetition, written in {n}, {n,} or {n,m}: at most 134,217,727. */
    case RepetitionCount;
    /** Groups one inside another: at most 200. */
    case NestingDepth;
    /**
     * The states of the pattern with its repetitions written out, counted from the text without
     * building anything: at most 250,000.
     */
    case MachineStates;

    /**
     * The greatest count, depth or number of states within the limit.
     */
    public function most(): int
    {
        return match ($this) {
            self::RepetitionCount => 134_217_727,
            self::NestingDepth => 200,
            self::MachineStates => 250_000,
        };
    }
}
