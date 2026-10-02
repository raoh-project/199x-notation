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
     * The meaning of the part $root of $written with every anchor read as what it comes to, or
     * null where one cannot be settled.
     */
    public static function place(Tree $written, int $root): ?Meaning
    {
        $known = self::factsOf($written, $root);
        $meaning = new Tree();
        // A part to place, standing where its two sides say, or, where together, one whose parts
        // are placed and whose meaning is to be put together from them; each a number (see task),
        // since there are as many as the tree has parts.
        $tasks = [self::task($root, self::YES, self::YES, false)];
        /** @var list<int> $results */
        $results = [];
        while ($tasks !== []) {
            $task = array_pop($tasks);
            $part = $task >> 5;
            $together = ($task & 16) !== 0;
            $atStart = ($task >> 2) & 3;
            $atEnd = $task & 3;
            if ($together) {
                self::putTogether($written, $part, $meaning, $results);
                continue;
            }
            $parts = $written->partsOf($part);
            switch ($written->kind[$part]) {
                case Tree::START:
                case Tree::END:
                    $end = $written->kind[$part] === Tree::END;
                    $made = self::anchor($meaning, $end, $end ? $atEnd : $atStart);
                    if ($made === null) {
                        return null;
                    }
                    $results[] = $made;
                    break;
                case Tree::EITHER_OF:
                    // Every arm of a choice begins where the choice begins and ends where it ends.
                    $tasks[] = self::task($part, $atStart, $atEnd, true);
                    for ($at = count($parts) - 1; $at >= 0; $at--) {
                        $tasks[] = self::task($parts[$at], $atStart, $atEnd, false);
                    }
                    break;
                case Tree::IN_TURN:
                    $tasks[] = self::task($part, $atStart, $atEnd, true);
                    $sides = self::sidesOf($parts, $known, $atStart, $atEnd);
                    for ($at = count($parts) - 1; $at >= 0; $at--) {
                        $tasks[] = self::task($parts[$at], $sides[$at][0], $sides[$at][1], false);
                    }
                    break;
                case Tree::REPEATED:
                    if (($known[$parts[0]] & self::HOLDS) === 0) {
                        $tasks[] = self::task($part, $atStart, $atEnd, true);
                        $tasks[] = self::task($parts[0], $atStart, $atEnd, false);
                    } elseif ($written->least($part) === 1 && $written->most($part) === 1) {
                        // One copy is the thing itself and stands where the repetition stands.
                        $tasks[] = self::task($parts[0], $atStart, $atEnd, false);
                    } else {
                        // Any other count leaves how many copies come before the anchor to the
                        // string.
                        return null;
                    }
                    break;
                default:
                    // A set of symbols, or nothing, which means what it is written as.
                    $results[] = $meaning->copyLeaf($written, $part);
            }
        }
        return new Meaning($meaning, $results[0]);
    }

    /**
     * A part to place as one number: the part, whether it is to be put together, and where it
     * stands at either end, two bits each.
     */
    private static function task(int $part, int $atStart, int $atEnd, bool $together): int
    {
        return ($part << 5) | ($together ? 16 : 0) | ($atStart << 2) | $atEnd;
    }

    /**
     * What an anchor standing at $at comes to, or null where that cannot be settled. ^ asks to be
     * at the start of the string and there is one such place, so anything that must take a symbol
     * before it leaves no string at all. A $ with something after it that must take a symbol is
     * refused rather than read the same way: the language keeps the set of patterns it reads, and
     * that set has no pattern of this shape.
     */
    private static function anchor(Tree $meaning, bool $end, int $at): ?int
    {
        return match ($at) {
            self::YES => $meaning->leaf(Tree::NOTHING),
            self::NO => $end ? null : $meaning->leaf(Tree::NEVER),
            default => null,
        };
    }

    /**
     * Takes the meanings of the parts of $part off the end of $results, and puts its meaning
     * there. The list is changed where it is, so that putting a part together costs its own parts
     * and not the meanings waiting before them.
     *
     * @param list<int> $results
     */
    private static function putTogether(Tree $written, int $part, Tree $meaning, array &$results): void
    {
        $kind = $written->kind[$part];
        if ($kind === Tree::REPEATED) {
            $repeated = array_pop($results) ?? throw new \LogicException('a repetition with nothing placed to repeat');
            $results[] = $meaning->repeated($repeated, $written->least($part), $written->most($part));
            return;
        }
        $taken = [];
        for ($i = count($written->partsOf($part)); $i > 0; $i--) {
            $taken[] = array_pop($results) ?? throw new \LogicException('a part with fewer meanings placed than it has parts');
        }
        $taken = array_reverse($taken);
        if ($kind === Tree::EITHER_OF) {
            $results[] = $meaning->of(Tree::EITHER_OF, $taken);
            return;
        }
        // A sequence. An anchor that asks for nothing leaves nothing in it, so ^abc$ means what abc
        // means and is the same tree.
        $parts = [];
        foreach ($taken as $made) {
            if ($meaning->kind[$made] !== Tree::NOTHING) {
                $parts[] = $made;
            }
        }
        $results[] = match (count($parts)) {
            0 => $meaning->leaf(Tree::NOTHING),
            1 => $parts[0],
            default => $meaning->of(Tree::IN_TURN, $parts),
        };
    }

    /**
     * Where each part of a sequence stands: at the start of the string where nothing before it
     * takes a symbol and the sequence is there, and not there where everything before it must;
     * the same for the end. What stands before each part and after it is gathered once from each
     * end, so that a literal written out a symbol at a time does not cost its length squared.
     *
     * @param list<int>       $parts
     * @param array<int, int> $known
     * @return list<array{int, int}>
     */
    private static function sidesOf(array $parts, array $known, int $atStart, int $atEnd): array
    {
        $count = count($parts);
        $mayBefore = [false];
        $mustBefore = [true];
        foreach ($parts as $at => $part) {
            $mayBefore[$at + 1] = $mayBefore[$at] || ($known[$part] & self::MAY) !== 0;
            $mustBefore[$at + 1] = $mustBefore[$at] && ($known[$part] & self::MUST) !== 0;
        }
        $mayAfter = [$count => false];
        $mustAfter = [$count => true];
        for ($at = $count - 1; $at >= 0; $at--) {
            $mayAfter[$at] = $mayAfter[$at + 1] || ($known[$parts[$at]] & self::MAY) !== 0;
            $mustAfter[$at] = $mustAfter[$at + 1] && ($known[$parts[$at]] & self::MUST) !== 0;
        }
        $out = [];
        for ($at = 0; $at < $count; $at++) {
            $out[] = [
                self::beyond($mayBefore[$at], $mustBefore[$at], $atStart),
                self::beyond($mayAfter[$at + 1], $mustAfter[$at + 1], $atEnd),
            ];
        }
        return $out;
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
            $parts = $written->partsOf($top);
            if (($entry & 1) === 0 && $parts !== []) {
                $stack[] = ($top << 1) | 1;
                foreach ($parts as $part) {
                    $stack[] = $part << 1;
                }
                continue;
            }
            $known[$top] = self::partFacts($written, $top, $parts, $known);
        }
        return $known;
    }

    /**
     * The facts of $part, whose parts' facts are known.
     *
     * @param list<int>       $parts
     * @param array<int, int> $known
     */
    private static function partFacts(Tree $written, int $part, array $parts, array $known): int
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
                foreach ($parts as $each) {
                    $f |= $known[$each];
                }
                return $f;
            case Tree::EITHER_OF:
                // A choice may and holds where any arm does, and must where every arm does.
                $any = 0;
                $every = self::MUST;
                foreach ($parts as $arm) {
                    $any |= $known[$arm];
                    $every &= $known[$arm];
                }
                return ($any & (self::MAY | self::HOLDS)) | $every;
            case Tree::REPEATED:
                $what = $known[$parts[0]];
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
