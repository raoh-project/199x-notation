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
    // whether it holds an anchor. A bit and not an array, since there is one of these for each
    // part of a tree as deep as the text is nested.
    private const MAY = 1;
    private const MUST = 2;
    private const HOLDS = 4;

    /**
     * The meaning of $w with every anchor read as what it comes to, or null where one cannot be
     * settled. Every part of the meaning that holds others is held in $nodes as well.
     */
    public static function place(Written $w, Nodes $nodes): ?Meaning
    {
        $known = self::factsOf($w);
        // A part to place, standing where its two sides say, or, where together, one whose parts
        // are placed and whose meaning is to be put together from them.
        /** @var list<array{Written, int, int, bool}> $tasks */
        $tasks = [[$w, self::YES, self::YES, false]];
        /** @var list<Meaning> $results */
        $results = [];
        while ($tasks !== []) {
            [$part, $atStart, $atEnd, $together] = array_pop($tasks);
            if ($together) {
                self::putTogether($part, $results, $nodes);
                continue;
            }
            switch ($part->kind) {
                case Written::MEANT:
                    $results[] = $part->meaning();
                    break;
                case Written::ANCHOR:
                    $made = self::anchor($part->end, $part->end ? $atEnd : $atStart);
                    if ($made === null) {
                        return null;
                    }
                    $results[] = $made;
                    break;
                case Written::EITHER_OF:
                    // Every arm of a choice begins where the choice begins and ends where it ends.
                    $tasks[] = [$part, $atStart, $atEnd, true];
                    for ($at = count($part->parts) - 1; $at >= 0; $at--) {
                        $tasks[] = [$part->parts[$at], $atStart, $atEnd, false];
                    }
                    break;
                case Written::IN_TURN:
                    $tasks[] = [$part, $atStart, $atEnd, true];
                    $sides = self::sidesOf($part, $known, $atStart, $atEnd);
                    for ($at = count($part->parts) - 1; $at >= 0; $at--) {
                        $tasks[] = [$part->parts[$at], $sides[$at][0], $sides[$at][1], false];
                    }
                    break;
                case Written::REPEATED:
                    if (($known[spl_object_id($part->parts[0])] & self::HOLDS) === 0) {
                        $tasks[] = [$part, $atStart, $atEnd, true];
                        $tasks[] = [$part->parts[0], $atStart, $atEnd, false];
                    } elseif ($part->least === 1 && $part->most === 1) {
                        // One copy is the thing itself and stands where the repetition stands.
                        $tasks[] = [$part->parts[0], $atStart, $atEnd, false];
                    } else {
                        // Any other count leaves how many copies come before the anchor to the
                        // string.
                        return null;
                    }
                    break;
            }
        }
        return $results[0];
    }

    /**
     * What an anchor standing at $at comes to, or null where that cannot be settled. ^ asks to be
     * at the start of the string and there is one such place, so anything that must take a symbol
     * before it leaves no string at all. A $ with something after it that must take a symbol is
     * refused rather than read the same way: the language keeps the set of patterns it reads, and
     * that set has no pattern of this shape.
     */
    private static function anchor(bool $end, int $at): ?Meaning
    {
        return match ($at) {
            self::YES => Meaning::nothing(),
            self::NO => $end ? null : new Meaning(Meaning::NEVER),
            default => null,
        };
    }

    /**
     * Takes the meanings of $w's parts off the end of $results, and puts $w's meaning there. The
     * list is changed where it is, so that putting a part together costs its own parts and not
     * the meanings waiting before them.
     *
     * @param list<Meaning> $results
     */
    private static function putTogether(Written $w, array &$results, Nodes $nodes): void
    {
        if ($w->kind === Written::REPEATED) {
            $repeated = array_pop($results) ?? throw new \LogicException('a repetition with nothing placed to repeat');
            $results[] = $nodes->held(new Meaning(Meaning::REPEATED, parts: [$repeated], least: $w->least, most: $w->most));
            return;
        }
        $taken = [];
        for ($i = count($w->parts); $i > 0; $i--) {
            $taken[] = array_pop($results) ?? throw new \LogicException('a part with fewer meanings placed than it has parts');
        }
        $taken = array_reverse($taken);
        if ($w->kind === Written::EITHER_OF) {
            $results[] = $nodes->held(new Meaning(Meaning::EITHER_OF, parts: $taken));
            return;
        }
        // A sequence. An anchor that asks for nothing leaves nothing in it, so ^abc$ means what abc
        // means and is the same tree.
        $parts = [];
        foreach ($taken as $made) {
            if ($made->kind !== Meaning::NOTHING) {
                $parts[] = $made;
            }
        }
        $results[] = match (count($parts)) {
            0 => Meaning::nothing(),
            1 => $parts[0],
            default => $nodes->held(new Meaning(Meaning::IN_TURN, parts: $parts)),
        };
    }

    /**
     * Where each part of a sequence stands: at the start of the string where nothing before it
     * takes a symbol and the sequence is there, and not there where everything before it must;
     * the same for the end. What stands before each part and after it is gathered once from each
     * end, so that a literal written out a symbol at a time does not cost its length squared.
     *
     * @param array<int, int> $known
     * @return list<array{int, int}>
     */
    private static function sidesOf(Written $w, array $known, int $atStart, int $atEnd): array
    {
        $count = count($w->parts);
        $mayBefore = [false];
        $mustBefore = [true];
        foreach ($w->parts as $at => $part) {
            $facts = $known[spl_object_id($part)];
            $mayBefore[$at + 1] = $mayBefore[$at] || ($facts & self::MAY) !== 0;
            $mustBefore[$at + 1] = $mustBefore[$at] && ($facts & self::MUST) !== 0;
        }
        $mayAfter = [$count => false];
        $mustAfter = [$count => true];
        for ($at = $count - 1; $at >= 0; $at--) {
            $facts = $known[spl_object_id($w->parts[$at])];
            $mayAfter[$at] = $mayAfter[$at + 1] || ($facts & self::MAY) !== 0;
            $mustAfter[$at] = $mustAfter[$at + 1] && ($facts & self::MUST) !== 0;
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
     * The facts of every part of $w, by the part's object id, worked out from the leaves up. A part is pushed once to have its parts worked
     * out and once more, below them, to be worked out from theirs. Every part stays held by $w, so
     * no id is reused while the table is.
     *
     * @return array<int, int>
     */
    private static function factsOf(Written $w): array
    {
        $known = [];
        /** @var list<array{Written, bool}> $stack */
        $stack = [[$w, false]];
        while ($stack !== []) {
            [$top, $partsDone] = array_pop($stack);
            if (!$partsDone && $top->parts !== []) {
                $stack[] = [$top, true];
                foreach ($top->parts as $part) {
                    $stack[] = [$part, false];
                }
                continue;
            }
            $known[spl_object_id($top)] = self::partFacts($top, $known);
        }
        return $known;
    }

    /**
     * The facts of $w, whose parts' facts are known.
     *
     * @param array<int, int> $known
     */
    private static function partFacts(Written $w, array $known): int
    {
        switch ($w->kind) {
            case Written::MEANT:
                return (self::mayTake($w->meaning()) ? self::MAY : 0) | (self::mustTake($w->meaning()) ? self::MUST : 0);
            case Written::ANCHOR:
                return self::HOLDS;
            case Written::IN_TURN:
                // A sequence may, must and holds where any part does.
                $f = 0;
                foreach ($w->parts as $part) {
                    $f |= $known[spl_object_id($part)];
                }
                return $f;
            case Written::EITHER_OF:
                // A choice may and holds where any arm does, and must where every arm does.
                $any = 0;
                $every = self::MUST;
                foreach ($w->parts as $arm) {
                    $any |= $known[spl_object_id($arm)];
                    $every &= $known[spl_object_id($arm)];
                }
                return ($any & (self::MAY | self::HOLDS)) | $every;
            default:
                $what = $known[spl_object_id($w->parts[0])];
                return (($w->most === Meaning::NO_CEILING || $w->most > 0) ? $what & self::MAY : 0)
                    | ($w->least > 0 ? $what & self::MUST : 0)
                    | ($what & self::HOLDS);
        }
    }

    /**
     * Whether $m accepts any string of one symbol or more. A meaning the reader writes before the
     * anchors are placed is a set of symbols or nothing, so this is never deep.
     */
    private static function mayTake(Meaning $m): bool
    {
        switch ($m->kind) {
            case Meaning::SYMBOLS:
                return true;
            case Meaning::IN_TURN:
            case Meaning::EITHER_OF:
                foreach ($m->parts as $part) {
                    if (self::mayTake($part)) {
                        return true;
                    }
                }
                return false;
            case Meaning::REPEATED:
                return ($m->most === Meaning::NO_CEILING || $m->most > 0) && self::mayTake($m->parts[0]);
            default:
                return false;
        }
    }

    /**
     * Whether every string $m accepts has a symbol in it. Never accepts no string, so none of the
     * strings it accepts is the empty one.
     */
    private static function mustTake(Meaning $m): bool
    {
        switch ($m->kind) {
            case Meaning::NEVER:
            case Meaning::SYMBOLS:
                return true;
            case Meaning::IN_TURN:
                foreach ($m->parts as $part) {
                    if (self::mustTake($part)) {
                        return true;
                    }
                }
                return false;
            case Meaning::EITHER_OF:
                foreach ($m->parts as $arm) {
                    if (!self::mustTake($arm)) {
                        return false;
                    }
                }
                return true;
            case Meaning::REPEATED:
                return $m->least > 0 && self::mustTake($m->parts[0]);
            default:
                return false;
        }
    }
}
