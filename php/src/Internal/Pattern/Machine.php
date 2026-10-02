<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

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
 * Every pattern within the limits has a machine, up to 250,000 states, and the machine is held so
 * that one that large is built and walked within PHP's default memory limit. A PHP array is a
 * table of its own whatever it holds, so nothing here is an array per state: the steps are flat
 * lists of numbers, each state's steps a run of them from an offset (the layout called compressed
 * sparse rows), and a set of symbols is held once and named by its number. The sets a walk has
 * worked out are held the same way, by number, so no set holds another and forgetting them is
 * letting go of a few lists.
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

    /**
     * How much walking without kept sets is done after keeping them is given up on, before keeping
     * them is tried again: counted as one for each walk, for the set it starts in, and one for each
     * byte of the subject walked. Each time a try ends in giving up again the wait doubles, so what
     * the tries cost stays a part of that walking that gets smaller.
     *
     * It is what was walked and not what was handed in that is counted. An empty subject is walked
     * too, from the set a walk starts in, and so many of them lead to a try as surely as long ones
     * do; a long subject turned away at its first character counts that character, not its length.
     */
    public static int $retryWork = 16 * (2 << 20);

    /** About what one number in a list takes. */
    private const NUMBER_BYTES = 16;
    /** About what one step a kept set holds takes. */
    private const STEP_BYTES = 48;

    private int $accept = 0;
    /** @var array<int, int> where each state's steps over a symbol begin in $stepTo, and one more where the last one's end */
    private array $stepStart = [];
    /** @var array<int, int> where each step leads */
    private array $stepTo = [];
    /** @var array<int, int> which set each step is over */
    private array $stepOver = [];
    /** @var array<int, int> where each state's steps for nothing begin in $freeTo, and one more */
    private array $freeStart = [];
    /** @var array<int, int> */
    private array $freeTo = [];
    /** The sets of symbols steps are over, the same numbers the pattern's trees name them by. */
    private SymbolSets $sets;
    /** @var list<int> the sets' offsets, as $sets holds them, held here for the walk to read directly */
    private array $setStart = [];
    /** @var list<int> the sets' ranges, as $sets holds them */
    private array $setRanges = [];

    // The sets of states a walk has been in, by number: the sets a deterministic machine would
    // have, made only as a walk comes to them, and where the characters read from each lead.

    /** @var array<string, int> each kept set, by its states in order */
    private array $index = [];
    /** @var list<list<int>> the states of each kept set, ascending, every step for nothing taken */
    private array $keptStates = [];
    /** @var list<bool> */
    private array $keptAccepts = [];
    /** @var list<array<string, int>> where each character read from each kept set leads, by its UTF-8 bytes */
    private array $keptNext = [];
    /**
     * @var array<int, string> for each kept set, the ASCII characters it has been seen to lead back to
     *      itself by, so that a run of them is gone past at once
     */
    private array $keptLoop = [];
    /** The kept set a walk starts in, or -1 where it is not known. */
    private int $first = -1;
    private int $bytes = 0;
    /** The sets made and the characters read since the sets were last forgotten. */
    private int $made = 0;
    private int $read = 0;
    /** Whether keeping sets was given up on. */
    private bool $off = false;
    /** What has been walked since keeping sets was given up on, counted as $retryWork says. */
    private int $offWork = 0;
    /** How much is walked before keeping sets is tried again, $retryWork where it is nought. */
    private int $offFor = 0;
    /** Whether keeping sets is being tried again, so that giving up waits longer. */
    private bool $retrying = false;

    private function __construct()
    {
    }

    /**
     * The machine $m is run as. It refuses nothing: $m was read within the states limit, and
     * building makes no more states than the count.
     */
    public static function build(Meaning $m): self
    {
        $b = new MachineBuilder($m->tree);
        $machine = new self();
        $machine->accept = $b->part($m->root, $b->state());
        [$machine->stepStart, $machine->stepTo, $machine->stepOver] = self::rows($b->states, $b->stepFrom, $b->stepTo, $b->stepOver);
        [$machine->freeStart, $machine->freeTo] = self::rows($b->states, $b->freeFrom, $b->freeTo, null);
        $machine->sets = $m->tree->sets;
        $machine->setStart = $m->tree->sets->start;
        $machine->setRanges = $m->tree->sets->ranges;
        return $machine;
    }

    /**
     * Steps given as where each comes from, laid out as rows: an offset for each state and the
     * steps from it one after another, in the order they were made.
     *
     * @param list<int>      $from
     * @param list<int>      $to
     * @param list<int>|null $over
     * @return array{array<int, int>, array<int, int>, array<int, int>}
     */
    private static function rows(int $states, array $from, array $to, ?array $over): array
    {
        $start = array_fill(0, $states + 1, 0);
        foreach ($from as $q) {
            $start[$q + 1]++;
        }
        for ($q = 0; $q < $states; $q++) {
            $start[$q + 1] += $start[$q];
        }
        $next = $start;
        $count = count($from);
        $rowTo = array_fill(0, $count, 0);
        $rowOver = $over === null ? [] : array_fill(0, $count, 0);
        foreach ($from as $i => $q) {
            $at = $next[$q]++;
            $rowTo[$at] = $to[$i];
            if ($over !== null) {
                $rowOver[$at] = $over[$i];
            }
        }
        return [$start, $rowTo, $rowOver];
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
            // A run of ASCII characters each of which leads from the set the walk is in back to
            // it, gone past in one call: the walk is in the same set after it, and only a step
            // already worked out is taken so. A checkpoint added to a match later bounds the run
            // by strspn's length.
            if ($in >= 0 && $this->keptLoop[$in] !== '') {
                $run = strspn($subject, $this->keptLoop[$in], $at);
                if ($run > 0) {
                    $at += $run;
                    $this->read = self::grown($this->read, $run);
                    continue;
                }
            }
            $width = Utf8::width(ord($subject[$at]));
            $character = $width === 1 ? $subject[$at] : substr($subject, $at, $width);
            $at += $width;
            if ($in >= 0) {
                $next = $this->keptNext[$in][$character] ?? -1;
                if ($next >= 0) {
                    $in = $next;
                    $this->read = self::grown($this->read, 1);
                    if ($this->keptStates[$next] === []) {
                        return false;
                    }
                    continue;
                }
            }
            if (!$this->take($in, $now, $character)) {
                return false;
            }
        }
        if ($in >= 0) {
            return $this->keptAccepts[$in];
        }
        return isset($now[$this->accept]);
    }

    /**
     * Puts the walk in the state it starts in, with every state the steps for nothing reach from
     * it: the kept set it starts in where that is kept, and otherwise those states in $now.
     *
     * @param array<int, true> $now
     */
    private function begin(array &$now): int
    {
        if ($this->first >= 0) {
            return $this->first;
        }
        $now = [];
        $this->enter($now, 0);
        // While keeping sets is given up on, it is tried again once what has been walked since is
        // what it waits for.
        if ($this->off) {
            if ($this->offWork < $this->wait()) {
                $this->walkedAlone(1);
                return -1;
            }
            $this->off = false;
            $this->retrying = true;
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
    private function take(int &$in, array &$now, string $character): bool
    {
        $symbol = Utf8::decode($character);
        if ($in < 0) {
            $now = $this->advance(array_keys($now), $symbol);
            // The one place a walk without kept sets steps, so what such walks walk is counted
            // here, whether keeping sets was given up on before the walk or during it.
            $this->walkedAlone(strlen($character));
            return $now !== [];
        }
        $this->read = self::grown($this->read, 1);
        $from = $in;
        $now = $this->advance($this->keptStates[$from], $symbol);
        [$next, $forgot] = $this->keep($now);
        // $from was forgotten to make room where $forgot, and is not looked up again. Where an
        // ASCII character leads is room every set is charged for when it is kept; where another
        // leads is kept only where there is room for it.
        if ($next >= 0 && !$forgot && (strlen($character) === 1 || $this->charge(self::STEP_BYTES))) {
            $this->keptNext[$from][$character] = $next;
            if ($next === $from && strlen($character) === 1) {
                $this->keptLoop[$from] .= $character;
            }
        }
        $in = $next;
        if ($next >= 0) {
            return $this->keptStates[$next] !== [];
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
            for ($i = $this->stepStart[$q], $end = $this->stepStart[$q + 1]; $i < $end; $i++) {
                $to = $this->stepTo[$i];
                if (isset($next[$to])) {
                    continue;
                }
                // Most sets are one range, asked here without a search.
                $set = $this->stepOver[$i];
                $from = $this->setStart[$set];
                $holds = $this->setStart[$set + 1] - $from === 2
                    ? $this->setRanges[$from] <= $symbol && $symbol <= $this->setRanges[$from + 1]
                    : $this->sets->holds($set, $symbol);
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
            for ($i = $this->freeStart[$from], $end = $this->freeStart[$from + 1]; $i < $end; $i++) {
                $to = $this->freeTo[$i];
                if (!isset($into[$to])) {
                    $into[$to] = true;
                    $pending[] = $to;
                }
            }
        }
    }

    /**
     * The kept set $now holds, kept where it is not already, or -1 where keeping sets is given up
     * on; and whether the sets kept before were forgotten to make room for it.
     *
     * Which sets are kept, and whether any are, changes how fast a walk is and no answer. Where
     * they would be more than about $knownBytes, they are forgotten and worked out again as they
     * are come to. Where what was worked out since they were last forgotten was looked up again
     * less than once in ten, keeping them saves nothing: the walk goes on without them, and so do
     * the walks of the machine after it, until they have walked $retryWork. Then keeping sets is
     * tried again, as by a machine that has kept none: a pattern may be held for long, and what was
     * looked up too seldom then says nothing about the subjects read later. A try that gives up
     * again waits twice as long before the next, and one that keeps sets long enough to forget them
     * waits again as long as the first. A machine whose sets are large, or subjects that keep
     * coming to new ones, are walked a state at a time as without them, but for the tries.
     *
     * @param array<int, true> $now
     * @return array{int, bool}
     */
    private function keep(array $now): array
    {
        // A set is the same set in any order, and its key is written of its states in order.
        $states = array_keys($now);
        sort($states);
        $key = implode(',', $states);
        $set = $this->index[$key] ?? -1;
        if ($set >= 0) {
            return [$set, false];
        }
        $cost = 2 * strlen($key) + self::NUMBER_BYTES * count($states) + 128 * self::STEP_BYTES + 128;
        $forgot = false;
        if (!$this->charge($cost)) {
            $gaveUp = $this->read < 10 * $this->made || $cost > self::$knownBytes;
            $this->forget();
            if ($gaveUp) {
                $this->giveUp();
                return [-1, true];
            }
            // The sets were looked up often enough to be worth keeping, so a later give-up waits
            // as long as the first.
            $this->offFor = 0;
            $this->retrying = false;
            $forgot = true;
            $this->charge($cost);
        }
        $set = count($this->keptStates);
        $this->keptStates[] = $states;
        $this->keptAccepts[] = isset($now[$this->accept]);
        $this->keptNext[] = [];
        $this->keptLoop[] = '';
        $this->index[$key] = $set;
        $this->made++;
        return [$set, $forgot];
    }

    /**
     * How much is walked without kept sets before keeping them is tried again.
     */
    private function wait(): int
    {
        return $this->offFor === 0 ? self::$retryWork : $this->offFor;
    }

    /**
     * Walks the walks after this without kept sets, until wait() is walked.
     */
    private function giveUp(): void
    {
        if ($this->retrying) {
            $this->offFor = self::grown($this->wait(), $this->wait());
        }
        $this->retrying = false;
        $this->off = true;
        // The walk that gave up goes on without kept sets from the set it is in, which counts one.
        $this->offWork = 1;
    }

    /**
     * Counts what a walk without kept sets walked toward trying to keep them again, as $retryWork
     * says.
     */
    private function walkedAlone(int $work): void
    {
        $this->offWork = self::grown($this->offWork, $work);
    }

    /**
     * $a + $b for counts that only grow, held at the most an int holds rather than becoming a
     * float, which a typed property would refuse.
     */
    public static function grown(int $a, int $b): int
    {
        return $a > PHP_INT_MAX - $b ? PHP_INT_MAX : $a + $b;
    }

    /**
     * Takes $bytes of the room the kept sets have, and is false, taking nothing, where there is
     * not that much left. Every set and every step the kept sets hold is charged here, which is
     * what holds them to the room.
     */
    private function charge(int $bytes): bool
    {
        if ($this->bytes + $bytes > self::$knownBytes) {
            return false;
        }
        $this->bytes += $bytes;
        return true;
    }

    /**
     * Forgets every set kept. A number a walk holds names no set afterwards, and a walk that
     * forgot goes on from the set it was making.
     */
    private function forget(): void
    {
        $this->index = [];
        $this->keptStates = [];
        $this->keptAccepts = [];
        $this->keptNext = [];
        $this->keptLoop = [];
        $this->first = -1;
        $this->bytes = 0;
        $this->made = 0;
        $this->read = 0;
    }
}
