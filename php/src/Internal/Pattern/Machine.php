<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

use Raoh\Notation199x\Internal\Ranges;
use Raoh\Notation199x\Internal\Utf8;
use Raoh\Notation199x\ScalarValues;

/**
 * The strings a pattern accepts, as states to walk between: an automaton with steps that cost a
 * symbol out of a set, and steps that cost nothing. A choice is a step into either arm and a
 * repetition a step back to where it started, so the machine has the states its pattern is counted
 * at and no more.
 *
 * What labels a step is a set of symbols and never one, so a step is as cheap for [^a] as for a.
 *
 * @internal
 */
final class Machine
{
    /**
     * About how much the sets a machine has worked out may take, in bytes, before they are
     * forgotten and worked out again as they are come to.
     */
    public static int $knownBytes = 2 << 20;

    /** @var list<list<array{list<int>, int}>> each state's steps: the symbols it is over and where it leads */
    private array $steps = [];
    /** @var list<list<int>> each state's steps for nothing */
    private array $free = [];
    private int $accept = 0;

    /** @var array<string, KnownSet> the sets worked out, by their states in order */
    private array $index = [];
    /** The set a walk starts in, or null where it is not known. */
    private ?KnownSet $first = null;
    private int $bytes = 0;
    /** The sets made and the characters read since the sets were last forgotten. */
    private int $made = 0;
    private int $read = 0;
    /** Whether keeping sets was given up on. */
    private bool $off = false;

    private function __construct()
    {
    }

    /**
     * The machine $m is run as. It refuses nothing: $m was read within the states limit, and
     * building makes no more states than the count.
     */
    public static function build(Meaning $m): self
    {
        $b = new self();
        $start = $b->state();
        $b->accept = $b->part($m, $start);
        return $b;
    }

    private function state(): int
    {
        $this->steps[] = [];
        $this->free[] = [];
        return count($this->steps) - 1;
    }

    /**
     * Makes the states for $m, walked into from $from, and answers where it leaves off: one entry
     * and one exit apiece, which is what lets the shapes compose without any of them knowing what
     * it is inside. Recursive, since a pattern that was read nests no deeper than the nesting
     * depth.
     */
    private function part(Meaning $m, int $from): int
    {
        switch ($m->kind) {
            case Meaning::NOTHING:
                return $from;
            case Meaning::NEVER:
                // Nothing leads out of it, so nothing after it is reached.
                return $this->state();
            case Meaning::SYMBOLS:
                $to = $this->state();
                $this->steps[$from][] = [$m->held, $to];
                return $to;
            case Meaning::IN_TURN:
                $at = $from;
                foreach ($m->parts as $part) {
                    $at = $this->part($part, $at);
                }
                return $at;
            case Meaning::EITHER_OF:
                $out = $this->state();
                foreach ($m->parts as $arm) {
                    $in = $this->state();
                    $this->free[$from][] = $in;
                    $this->free[$this->part($arm, $in)][] = $out;
                }
                return $out;
            default:
                return $this->repeated($m, $from);
        }
    }

    /**
     * Makes a repetition as the copies it is: the floor is copies one after another, what is above
     * it is copies each of which may be stepped over, and an unbounded ceiling is one more copy
     * with a step back to where it began.
     */
    private function repeated(Meaning $m, int $from): int
    {
        $what = $m->parts[0];
        // A body that makes no state is the empty string however many times it is taken, and is
        // built as that: one state to end in. Copied a count at a time it would cost the count and
        // make nothing.
        if (self::buildsNoState($what)) {
            $out = $this->state();
            $this->free[$from][] = $out;
            return $out;
        }
        $at = $from;
        for ($i = 0; $i < $m->least; $i++) {
            $at = $this->part($what, $at);
        }
        if ($m->most === Meaning::NO_CEILING) {
            $loop = $this->state();
            $this->free[$at][] = $loop;
            $this->free[$this->part($what, $loop)][] = $loop;
            return $loop;
        }
        $out = $this->state();
        $this->free[$at][] = $out;
        for ($i = $m->least; $i < $m->most; $i++) {
            $at = $this->part($what, $at);
            $this->free[$at][] = $out;
        }
        return $out;
    }

    /**
     * Whether building $m makes no state, which is only ever the empty string.
     */
    private static function buildsNoState(Meaning $m): bool
    {
        if ($m->kind === Meaning::NOTHING) {
            return true;
        }
        if ($m->kind !== Meaning::IN_TURN) {
            return false;
        }
        foreach ($m->parts as $part) {
            if (!self::buildsNoState($part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the whole of $subject is accepted: every state the machine may be in is walked at
     * once, a scalar value at a time, and nothing is gone back over. Bytes that are not UTF-8 are
     * no text, and are accepted by nothing.
     *
     * Where the set the walk is in is kept, a character whose step from it is known is taken here,
     * as one lookup. Every other character is taken by take.
     */
    public function matches(string $subject): bool
    {
        if (ScalarValues::invalidUtf8At($subject) !== null) {
            return false;
        }
        $now = [];
        $in = $this->begin($now);
        $length = strlen($subject);
        for ($at = 0; $at < $length;) {
            $width = Utf8::width(ord($subject[$at]));
            $character = $width === 1 ? $subject[$at] : substr($subject, $at, $width);
            $at += $width;
            if ($in !== null) {
                $next = $in->next[$character] ?? null;
                if ($next !== null) {
                    $in = $next;
                    $this->read++;
                    if ($next->none) {
                        return false;
                    }
                    continue;
                }
            }
            if (!$this->take($in, $now, $character)) {
                return false;
            }
        }
        if ($in !== null) {
            return $in->accepts;
        }
        return isset($now[$this->accept]);
    }

    /**
     * Puts the walk in the state it starts in, with every state the steps for nothing reach from
     * it: the kept set it starts in where that is kept, and otherwise those states in $now.
     *
     * @param array<int, true> $now
     */
    private function begin(array &$now): ?KnownSet
    {
        if ($this->first !== null) {
            return $this->first;
        }
        $now = [];
        $this->enter($now, 0);
        if ($this->off) {
            return null;
        }
        [$this->first] = $this->keep($now);
        return $this->first;
    }

    /**
     * Moves the walk over one character, and is false where it is in no state after it. Where the
     * set it is in is kept, where the character leads from it is worked out and kept; otherwise
     * the set in $now is moved by advance. Every step a walk takes that does work growing with the
     * machine is taken here.
     *
     * @param array<int, true> $now
     */
    private function take(?KnownSet &$in, array &$now, string $character): bool
    {
        $symbol = Utf8::decode($character);
        if ($in === null) {
            $now = $this->advance(array_keys($now), $symbol);
            return $now !== [];
        }
        $this->read++;
        $from = $in;
        $now = $this->advance($from->states, $symbol);
        [$next, $forgot] = $this->keep($now);
        if ($next !== null && !$forgot) {
            // $from was forgotten to make room where $forgot, and is not looked up again.
            $from->next[$character] = $next;
            $this->bytes += 48;
        }
        $in = $next;
        if ($next !== null) {
            return !$next->none;
        }
        return $now !== [];
    }

    /**
     * The set the states $from come to over $symbol: from each state, each step over it, and the
     * states the steps for nothing reach from where those lead. The one place states are moved,
     * and its work is the steps out of the set and the states it comes to, at most the machine's.
     * Keeping a set not kept before is work of the same size besides (keep), so a checkpoint added
     * to a match later asks in both.
     *
     * @param list<int> $from
     * @return array<int, true>
     */
    private function advance(array $from, int $symbol): array
    {
        $next = [];
        foreach ($from as $q) {
            foreach ($this->steps[$q] as [$over, $to]) {
                if (isset($next[$to])) {
                    continue;
                }
                // Most sets are one range, asked here without a search.
                $holds = count($over) === 2 ? $over[0] <= $symbol && $symbol <= $over[1] : Ranges::has($over, $symbol);
                if ($holds) {
                    $this->enter($next, $to);
                }
            }
        }
        return $next;
    }

    /**
     * Puts $q in $into, with every state the steps for nothing reach from it, each once.
     *
     * @param array<int, true> $into
     */
    private function enter(array &$into, int $q): void
    {
        if (isset($into[$q])) {
            return;
        }
        $into[$q] = true;
        $pending = [$q];
        while ($pending !== []) {
            $from = array_pop($pending);
            foreach ($this->free[$from] as $to) {
                if (!isset($into[$to])) {
                    $into[$to] = true;
                    $pending[] = $to;
                }
            }
        }
    }

    /**
     * The kept set $now holds, kept where it is not already, or null where keeping sets is given
     * up on; and whether the sets kept before were forgotten to make room for it.
     *
     * Which sets are kept, and whether any are, changes how fast a walk is and no answer. Where
     * they would be more than about $knownBytes, they are forgotten and worked out again as they
     * are come to. Where what was worked out since they were last forgotten was looked up again
     * less than once in ten, keeping them saves nothing: the walk goes on without them, and so
     * does every later walk of the machine. A machine whose sets are large, or subjects that keep
     * coming to new ones, are walked a state at a time as without them.
     *
     * @param array<int, true> $now
     * @return array{?KnownSet, bool}
     */
    private function keep(array $now): array
    {
        // A set is the same set in any order, and its key is written of its states in order.
        $states = array_keys($now);
        sort($states);
        $key = implode(',', $states);
        $set = $this->index[$key] ?? null;
        if ($set !== null) {
            return [$set, false];
        }
        $cost = 2 * strlen($key) + 16 * count($states) + 128;
        $forgot = false;
        if ($this->bytes + $cost > self::$knownBytes) {
            $gaveUp = $this->read < 10 * $this->made || $cost > self::$knownBytes;
            $this->forget();
            if ($gaveUp) {
                $this->off = true;
                return [null, true];
            }
            $forgot = true;
        }
        $set = new KnownSet($states, isset($now[$this->accept]), $states === []);
        $this->index[$key] = $set;
        $this->bytes += $cost;
        $this->made++;
        return [$set, $forgot];
    }

    /**
     * Forgets every set kept. A set held across it is still the set it was, and is no longer
     * looked up.
     */
    private function forget(): void
    {
        $this->index = [];
        $this->first = null;
        $this->bytes = 0;
        $this->made = 0;
        $this->read = 0;
    }
}
