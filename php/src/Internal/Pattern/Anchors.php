<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

/**
 * What the anchors in a pattern come to, given that the whole of it must match the whole string.
 *
 * Whole-string matching is what gives an anchor an answer. ^ asks to be at the start of the
 * string, so it is satisfied by every string where nothing before it can take a symbol and by
 * none where everything before it must: the empty string in the first case and never in the
 * second. $ is the same question about the end. Where neither holds, as in (a|)^b, the strings
 * accepted are settled by which arm a string took, which the language has no shape for, so the
 * pattern is refused. The same for an anchor under a repetition, where how many copies precede it
 * is not a thing the shape says.
 *
 * The reader asks this of text nested as deeply as it is long, before any limit says it is too
 * deep, because whether an anchor can be placed is part of whether the text is a pattern. So the
 * tree is walked with stacks of its own: once from the leaves up, for what each part may and must
 * take and whether it holds an anchor, and once from the root down, for where each part stands
 * and what it comes to.
 *
 * @internal
 */
final class Anchors
{
    // Whether an anchor is at the end it is asking about, as far as the shape says.
    private const YES = 0;
    private const NO = 1;
    private const UNSETTLED = 2;

    // What a part is, as far as the anchors around it ask, one bit each: whether it accepts any
    // string of one symbol or more, whether every string it accepts has a symbol in it, and
    // whether it holds an anchor.
    private const MAY = 1;
    private const MUST = 2;
    private const HOLDS = 4;

    /**
     * Reads every anchor under the part $root of $written as what it comes to, in the tree, and is
     * false, the tree left as it was read, where one cannot be settled.
     *
     * The tree is the meaning once this is done, and no other tree is made: an anchor becomes the
     * nothing or the never it comes to, and every other part already is its meaning. What that
     * leaves in place that a tree made afresh would not hold, a nothing in a sequence and a
     * repetition of exactly one, accepts the strings it would.
     */
    public static function place(Tree $written, int $root): bool
    {
        $known = self::factsOf($written, $root);
        // A part to place, standing where its two sides say, each a number (see task), since there
        // are as many as the tree has parts. Only a part that holds an anchor is gone into.
        $tasks = [self::task($root, self::YES, self::YES)];
        /** @var list<array{int, int}> $settled */
        $settled = [];
        while ($tasks !== []) {
            $task = array_pop($tasks);
            $part = $task >> 4;
            if (($known[$part] & self::HOLDS) === 0) {
                continue;
            }
            $atStart = ($task >> 2) & 3;
            $atEnd = $task & 3;
            $count = $written->partCount($part);
            switch ($written->kind[$part]) {
                case Tree::START:
                case Tree::END:
                    $end = $written->kind[$part] === Tree::END;
                    $kind = self::anchor($end, $end ? $atEnd : $atStart);
                    if ($kind === null) {
                        return false;
                    }
                    $settled[] = [$part, $kind];
                    break;
                case Tree::EITHER_OF:
                    // Every arm of a choice begins where the choice begins and ends where it ends.
                    for ($at = $count - 1; $at >= 0; $at--) {
                        $tasks[] = self::task($written->partAt($part, $at), $atStart, $atEnd);
                    }
                    break;
                case Tree::IN_TURN:
                    $sides = self::sidesOf($written, $part, $known, $atStart, $atEnd);
                    for ($at = $count - 1; $at >= 0; $at--) {
                        $tasks[] = self::task($written->partAt($part, $at), $sides[$at] >> 2, $sides[$at] & 3);
                    }
                    break;
                case Tree::REPEATED:
                    // One copy is the thing itself and stands where the repetition stands. Any other
                    // count leaves how many copies come before the anchor to the string.
                    if ($written->least($part) !== 1 || $written->most($part) !== 1) {
                        return false;
                    }
                    $tasks[] = self::task($written->repeats($part), $atStart, $atEnd);
                    break;
            }
        }
        // Written only once every anchor is known to be settled, so that a refused pattern's tree
        // is the one that was read.
        foreach ($settled as [$part, $kind]) {
            $written->settle($part, $kind);
        }
        return true;
    }

    /**
     * A part to place as one number: the part, and where it stands at either end, two bits each.
     */
    private static function task(int $part, int $atStart, int $atEnd): int
    {
        return ($part << 4) | ($atStart << 2) | $atEnd;
    }

    /**
     * What an anchor standing at $at comes to, or null where that cannot be settled. ^ asks to be
     * at the start of the string and there is one such place, so anything that must take a symbol
     * before it leaves no string at all. A $ with something after it that must take a symbol is
     * refused rather than read the same way: the language keeps the set of patterns it reads, and
     * that set has no pattern of this shape.
     */
    private static function anchor(bool $end, int $at): ?int
    {
        return match ($at) {
            self::YES => Tree::NOTHING,
            self::NO => $end ? null : Tree::NEVER,
            default => null,
        };
    }

    /**
     * Where each part of a sequence stands: at the start of the string where nothing before it
     * takes a symbol and the sequence is there, and not there where everything before it must;
     * the same for the end. What stands before each part and after it is gathered once from each
     * end, so that a literal written out a symbol at a time does not cost its length squared.
     *
     * Each part's two sides are one number, the start above the end, two bits each: a sequence is
     * as long as the text, and an array a part would take several times the room of its numbers.
     *
     * @param array<int, int> $known
     * @return list<int>
     */
    private static function sidesOf(Tree $written, int $sequence, array $known, int $atStart, int $atEnd): array
    {
        $count = $written->partCount($sequence);
        // Where each part stands at the end, from the last part back.
        $ends = [];
        $mayAfter = false;
        $mustAfter = true;
        for ($at = $count - 1; $at >= 0; $at--) {
            $ends[] = self::beyond($mayAfter, $mustAfter, $atEnd);
            $facts = $known[$written->partAt($sequence, $at)];
            $mayAfter = $mayAfter || ($facts & self::MAY) !== 0;
            $mustAfter = $mustAfter && ($facts & self::MUST) !== 0;
        }
        $sides = [];
        $mayBefore = false;
        $mustBefore = true;
        for ($at = 0; $at < $count; $at++) {
            $sides[] = (self::beyond($mayBefore, $mustBefore, $atStart) << 2) | $ends[$count - 1 - $at];
            $facts = $known[$written->partAt($sequence, $at)];
            $mayBefore = $mayBefore || ($facts & self::MAY) !== 0;
            $mustBefore = $mustBefore && ($facts & self::MUST) !== 0;
        }
        return $sides;
    }

    /**
     * Where a part stands, given what is on that side of it and where they all stand together.
     * Nothing on that side takes a symbol, so the part stands where they all do. Everything on
     * that side must take one, so it does not. Anything in between and the answer belongs to a
     * string rather than to the pattern.
     */
    private static function beyond(bool $anyTakes, bool $allTake, int $outer): int
    {
        return match (true) {
            !$anyTakes => $outer,
            $allTake => self::NO,
            default => self::UNSETTLED,
        };
    }

    /**
     * The facts of every part under $root, by part, worked out from the leaves up. A part is
     * pushed once to have its parts worked out and once more, below them, to be worked out from
     * theirs.
     *
     * @return array<int, int>
     */
    private static function factsOf(Tree $written, int $root): array
    {
        $known = [];
        // Each entry a part, twice, and whether its parts are done in the lowest bit.
        $stack = [$root << 1];
        while ($stack !== []) {
            $entry = array_pop($stack);
            $top = $entry >> 1;
            $count = $written->partCount($top);
            if (($entry & 1) === 0 && $count > 0) {
                $stack[] = ($top << 1) | 1;
                for ($i = 0; $i < $count; $i++) {
                    $stack[] = $written->partAt($top, $i) << 1;
                }
                continue;
            }
            $known[$top] = self::partFacts($written, $top, $count, $known);
        }
        return $known;
    }

    /**
     * The facts of $part, whose parts' facts are known.
     *
     * @param array<int, int> $known
     */
    private static function partFacts(Tree $written, int $part, int $count, array $known): int
    {
        switch ($written->kind[$part]) {
            case Tree::SYMBOLS:
                return self::MAY | self::MUST;
            case Tree::START:
            case Tree::END:
                return self::HOLDS;
            case Tree::IN_TURN:
                // A sequence may, must and holds where any part does.
                $f = 0;
                for ($i = 0; $i < $count; $i++) {
                    $f |= $known[$written->partAt($part, $i)];
                }
                return $f;
            case Tree::EITHER_OF:
                // A choice may and holds where any arm does, and must where every arm does.
                $any = 0;
                $every = self::MUST;
                for ($i = 0; $i < $count; $i++) {
                    $arm = $known[$written->partAt($part, $i)];
                    $any |= $arm;
                    $every &= $arm;
                }
                return ($any & (self::MAY | self::HOLDS)) | $every;
            case Tree::REPEATED:
                $what = $known[$written->repeats($part)];
                $most = $written->most($part);
                return (($most === Tree::NO_CEILING || $most > 0) ? $what & self::MAY : 0)
                    | ($written->least($part) > 0 ? $what & self::MUST : 0)
                    | ($what & self::HOLDS);
            default:
                // Nothing takes no symbol and holds no anchor.
                return 0;
        }
    }
}
