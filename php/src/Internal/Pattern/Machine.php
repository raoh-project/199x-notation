<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

use Raoh\Notation199x\Internal\Utf8;

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
     * forgotten and worked out again as they are come to. One set that alone takes more is kept
     * all the same, with no other but the set a walk starts in (keep).
     */
    public static int $knownBytes = 2 << 20;

    /** A walk keeps the sets it comes to, and has not forgotten the sets kept before it. */
    private const KEEPING = 0;
    /** A walk keeps the sets it comes to, and has forgotten the sets kept before it at least once. */
    private const KEEPING_AGAIN = 1;
    /** A walk keeps no more sets, and looks up among those kept each set it comes to. */
    private const FROZEN = 2;

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

    /**
     * @var array<int, int> each kept set at its hash or past it, -1 where a slot is empty: a power
     *      of two of them and at least twice as many as are kept, or none before a set is kept
     */
    private array $slots = [];
    /** @var list<list<int>> the states of each kept set, in the order the walk entered them, every step for nothing taken */
    private array $keptStates = [];
    /** @var list<int> the hash of each kept set, as hashOf sums it */
    private array $keptHashes = [];
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
     *
     * What a walk decides about keeping sets is its own and is not held by the machine: whether it
     * has forgotten the sets kept before it, whether it keeps any more, and the sets it made and
     * the characters it read by kept steps since it last forgot them ($mode, $made and $read here).
     * Every walk starts keeping sets, whatever the walks before it did, so no subject makes a walk
     * of a later one slower (settle).
     *
     * What a walk does that grows with the subject, the machine or the sets kept is done in these
     * loops and in no call to PHP, so a checkpoint added to a match later asks in each of them:
     * matches() over the subject; advanceStates() and advanceSet() over the states and their
     * steps, and enter() over the steps for nothing; hashOf(), same() and keep() over a set; find() and free() over the slots;
     * and grow() over the sets kept.
     *
     * LoopsTest holds this list to every loop a match reaches, apart from those it says are
     * bounded whatever the subject and the machine, and holds what a match calls of PHP to a list.
     */
    public function matches(string $subject): bool
    {
        $now = [];
        $mode = self::KEEPING;
        $made = 0;
        $read = 0;
        $in = $this->begin($now, $mode, $made, $read);
        $length = strlen($subject);
        for ($at = 0; $at < $length;) {
            if ($in >= 0) {
                // A run of ASCII characters each of which leads from the set the walk is in back
                // to it, gone past in one call: the walk is in the same set after it, and only a
                // step already worked out is taken so. A checkpoint added to a match later bounds
                // the run by strspn's length.
                if ($this->keptLoop[$in] !== '') {
                    $run = strspn($subject, $this->keptLoop[$in], $at, $length - $at);
                    if ($run > 0) {
                        $at += $run;
                        $read += $run;
                        continue;
                    }
                }
                // A character is looked up by its bytes, and only a character take has read is
                // there, so bytes that are not UTF-8 are never found and go on to take, which asks.
                $width = Utf8::width(ord($subject[$at]));
                $character = $width === 1 ? $subject[$at] : substr($subject, $at, $width);
                $next = $this->keptNext[$in][$character] ?? -1;
                if ($next >= 0) {
                    $at += $width;
                    $in = $next;
                    $read++;
                    if ($this->keptStates[$next] === []) {
                        return false;
                    }
                    continue;
                }
            }
            if (!$this->take($in, $now, $subject, $at, $mode, $made, $read)) {
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
     * it: the kept set it starts in, kept now where it was not.
     *
     * @param array<int, true> $now
     */
    private function begin(array &$now, int &$mode, int &$made, int &$read): int
    {
        if ($this->first >= 0) {
            return $this->first;
        }
        $now = [];
        $this->enter($now, 0);
        // A walk that starts keeps the set it starts in, forgetting the others where there is no
        // room, so it is never frozen here.
        [$this->first] = $this->settle($now, $mode, $made, $read);
        return $this->first;
    }

    /**
     * Moves the walk over the character at $at, and past it, and is false where it is in no state
     * after it or the bytes there are not UTF-8. Where the set it is in is kept, where the
     * character leads from it is worked out from its states by advanceStates, and kept unless the
     * walk is frozen; otherwise the set in $now is moved by advanceSet. Either way the set it comes
     * to is looked up among those kept (settle), so a frozen walk goes back to kept steps wherever
     * it comes to a kept set. Every step a walk takes that does work growing with the machine is
     * taken here, and each character a step is worked out for is read here and asked whether it is
     * UTF-8.
     *
     * @param array<int, true> $now
     */
    private function take(int &$in, array &$now, string $subject, int &$at, int &$mode, int &$made, int &$read): bool
    {
        // ASCII is read here, without asking: every byte below 0x80 is a character.
        $character = $subject[$at];
        $symbol = ord($character);
        $width = 1;
        if ($symbol >= 0x80) {
            [$symbol, $width] = Utf8::scalarAt($subject, $at);
            if ($symbol < 0) {
                return false;
            }
            $character = substr($subject, $at, $width);
        }
        $at += $width;
        if ($in < 0) {
            // Only a frozen walk is in no kept set.
            $now = $this->advanceSet($now, $symbol);
            [$in] = $this->settle($now, $mode, $made, $read);
            return $in >= 0 ? $this->keptStates[$in] !== [] : $now !== [];
        }
        $read++;
        $from = $in;
        $now = $this->advanceStates($this->keptStates[$from], $symbol);
        [$next, $forgot] = $this->settle($now, $mode, $made, $read);
        // $from was forgotten to make room where $forgot, and is not looked up again. A frozen
        // walk adds no step to the kept sets. Where an ASCII character leads is room every set is
        // charged for when it is kept; where another leads is kept only where there is room for
        // it.
        if ($next >= 0 && !$forgot && $mode !== self::FROZEN && ($width === 1 || $this->charge(self::STEP_BYTES))) {
            $this->keptNext[$from][$character] = $next;
            if ($next === $from && $width === 1) {
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
     * The kept set $now holds, and whether the sets kept before were forgotten to make room for
     * it: found among those kept, or kept now, or -1 where the walk is frozen and it is not kept.
     * This is where a walk decides what to do when there is no room for a set, and keep() only
     * says so.
     *
     * The first time in a walk, the sets kept before are forgotten, but the one a walk starts in
     * (forget), and the set is kept anew: they were kept by walks before this one, and what they
     * cost says nothing about the subject read now. After that, where the walk read by kept steps at least ten characters for each set it
     * made since it last forgot them, the sets were worth keeping and are forgotten again. So are
     * they where the walk made one set since, which with the set it starts in filled the room:
     * frozen, it would have little to look up, and keeping the next set costs about what walking
     * it a state at a time does. (?:x*){124998} so keeps the set every x leads to, which is larger
     * than the room alone (keep), and goes round it. Otherwise keeping them saves nothing, and the
     * walk is frozen for the rest of the subject: it keeps no more sets and no more steps, so what
     * it holds grows no further, and it goes a state at a time, looking up each set it comes to
     * among those kept, so that it takes kept steps again where it comes to one. A walk that keeps
     * coming to new sets is so walked a state at a time, but for the sets it made before it was
     * frozen, at most twice the room. The next walk starts keeping sets again.
     *
     * @param array<int, true> $now
     * @return array{int, bool}
     */
    private function settle(array $now, int &$mode, int &$made, int &$read): array
    {
        $hash = $this->hashOf($now);
        $set = $this->find($now, $hash);
        if ($set >= 0 || $mode === self::FROZEN) {
            return [$set, false];
        }
        $set = $this->keep($now, $hash, false);
        if ($set >= 0) {
            $made++;
            return [$set, false];
        }
        if ($mode === self::KEEPING_AGAIN && $made > 1 && $read < 10 * $made) {
            $mode = self::FROZEN;
            return [-1, false];
        }
        $this->forget();
        $mode = self::KEEPING_AGAIN;
        $made = 1;
        $read = 0;
        return [$this->keep($now, $hash, true), true];
    }

    /**
     * The set the states $from come to over $symbol: from each state, each step over it, and the
     * states the steps for nothing reach from where those lead. One of the two places states are
     * moved, with advanceSet, and its work is the steps out of the set and the states it comes to,
     * at most the machine's. Keeping a set not kept before is work of the same size besides
     * (keep), so a checkpoint added to a match later asks in each.
     *
     * $from is a kept set's states, as a list, which takes half the room a set keyed by its states
     * does. advanceSet moves a walk's own set, keyed by its states, and is this but for how it goes
     * over them: one loop that went over either was measured 2 to 3 percent slower on walks of
     * small sets, and copying a walk's set into a list costs a pass over it. MachineTest holds the
     * two to the same steps.
     *
     * @param list<int> $from
     * @return array<int, true>
     */
    private function advanceStates(array $from, int $symbol): array
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
                $range = $this->setStart[$set];
                $holds = $this->setStart[$set + 1] - $range === 2
                    ? $this->setRanges[$range] <= $symbol && $symbol <= $this->setRanges[$range + 1]
                    : $this->sets->holds($set, $symbol);
                if ($holds) {
                    $this->enter($next, $to);
                }
            }
        }
        return $next;
    }

    /**
     * advanceStates of the set a walk without kept sets is in, keyed by its states.
     *
     * @param array<int, true> $from
     * @return array<int, true>
     */
    private function advanceSet(array $from, int $symbol): array
    {
        $next = [];
        foreach ($from as $q => $_) {
            for ($i = $this->stepStart[$q], $end = $this->stepStart[$q + 1]; $i < $end; $i++) {
                $to = $this->stepTo[$i];
                if (isset($next[$to])) {
                    continue;
                }
                // Most sets are one range, asked here without a search.
                $set = $this->stepOver[$i];
                $range = $this->setStart[$set];
                $holds = $this->setStart[$set + 1] - $range === 2
                    ? $this->setRanges[$range] <= $symbol && $symbol <= $this->setRanges[$range + 1]
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
     * The set $now holds, whose hash is $hash and which is not kept, kept now; or -1 where there is
     * no room for it, and nothing is kept. Which sets are kept, and whether any are, changes how
     * fast a walk is and no answer. What to do where there is no room is the walk's to decide
     * (settle); this only holds the sets to about $knownBytes.
     *
     * With $always, the set is kept whatever room it takes. It is asked only just after the sets
     * were forgotten, so that a set that alone takes more than $knownBytes is kept, beside the set
     * a walk starts in and no other, and is not walked a state at a time at every character that
     * comes to it again, as (?:x*){124998} comes to the same set of nearly the whole machine at
     * every x. What the two take is bounded by twice the machine's states, and no step is kept
     * beside them but those the room of each set pays for, its ASCII characters.
     *
     * @param array<int, true> $now
     */
    private function keep(array $now, int $hash, bool $always): int
    {
        $cost = self::cost(count($now));
        if (!$this->charge($cost)) {
            if (!$always) {
                return -1;
            }
            $this->bytes += $cost;
        }
        $set = count($this->keptStates);
        if (($set + 1) * 2 > count($this->slots)) {
            $this->grow();
        }
        $states = [];
        foreach ($now as $q => $_) {
            $states[] = $q;
        }
        $this->keptStates[] = $states;
        $this->keptHashes[] = $hash;
        $this->keptAccepts[] = isset($now[$this->accept]);
        $this->keptNext[] = [];
        $this->keptLoop[] = '';
        $this->slots[$this->free($hash)] = $set;
        return $set;
    }

    /**
     * The hash a set is looked up by: a sum over its states that is the same in whatever order the
     * walk entered them, so the set is never put in order. It is summed where a set is looked up
     * and not as each state is entered, which a walk without kept sets does too.
     *
     * Each state is scattered by a multiplication and a shift, held to 32 bits; their sum is held
     * to 32 bits once at the end, since 250,000 of them stay far within an int.
     *
     * @param array<int, true> $now
     */
    private function hashOf(array $now): int
    {
        $hash = 0;
        foreach ($now as $q => $_) {
            $mixed = ($q * 0x9E3779B9) & 0xFFFFFFFF;
            $hash += $mixed ^ ($mixed >> 15);
        }
        return $hash & 0xFFFFFFFF;
    }

    /**
     * The kept set that is the set $now holds, whose hash is $hash, or -1 where it is not kept:
     * the slots from $hash on are probed until an empty one.
     *
     * @param array<int, true> $now
     */
    private function find(array $now, int $hash): int
    {
        if ($this->slots === []) {
            return -1;
        }
        $mask = count($this->slots) - 1;
        for ($at = $hash & $mask; ($held = $this->slots[$at]) >= 0; $at = ($at + 1) & $mask) {
            if ($this->keptHashes[$held] === $hash && $this->same($held, $now)) {
                return $held;
            }
        }
        return -1;
    }

    /**
     * Whether the kept set $held is the set $now holds: as many states, each of which $now has,
     * asked one at a time. Sets with the same hash are told apart here, so a hash shared by two
     * sets changes no answer.
     *
     * @param array<int, true> $now
     */
    private function same(int $held, array $now): bool
    {
        $states = $this->keptStates[$held];
        if (count($states) !== count($now)) {
            return false;
        }
        foreach ($states as $q) {
            if (!isset($now[$q])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first empty slot from $hash on.
     */
    private function free(int $hash): int
    {
        $mask = count($this->slots) - 1;
        $at = $hash & $mask;
        while ($this->slots[$at] >= 0) {
            $at = ($at + 1) & $mask;
        }
        return $at;
    }

    /**
     * Makes twice the slots, or sixteen, and puts each kept set in them again by its hash. What is
     * gone over is the sets kept, at most what $knownBytes holds.
     */
    private function grow(): void
    {
        $this->slots = array_fill(0, max(16, 2 * count($this->slots)), -1);
        foreach ($this->keptHashes as $set => $hash) {
            $this->slots[$this->free($hash)] = $set;
        }
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
     * Forgets every set kept but the one a walk starts in, which is kept again as the first, with
     * no step from it. A number a walk holds names no set afterwards, but for that one, and a walk
     * that forgot goes on from the set it was making.
     *
     * The set a walk starts in is every walk's, so it is not left for the next walk to work out
     * again, as RE2 keeps its start when it forgets its states. (?:x*){124998} starts in a set of
     * half the machine and goes round one of the whole of it, which is kept by forgetting the
     * others; with the start kept, the next walk works out the step between the two once and
     * takes it as one lookup after.
     */
    private function forget(): void
    {
        $first = $this->first;
        $states = $first >= 0 ? $this->keptStates[$first] : [];
        $hash = $first >= 0 ? $this->keptHashes[$first] : 0;
        $accepts = $first >= 0 && $this->keptAccepts[$first];
        $this->slots = [];
        $this->keptStates = [];
        $this->keptHashes = [];
        $this->keptAccepts = [];
        $this->keptNext = [];
        $this->keptLoop = [];
        $this->first = -1;
        $this->bytes = 0;
        if ($first < 0) {
            return;
        }
        $this->keptStates[] = $states;
        $this->keptHashes[] = $hash;
        $this->keptAccepts[] = $accepts;
        $this->keptNext[] = [];
        $this->keptLoop[] = '';
        $this->grow();
        $this->slots[$this->free($hash)] = 0;
        $this->first = 0;
        $this->bytes = self::cost(count($states));
    }

    /**
     * What a kept set of $states states takes: its states, its hash, where it leads for each
     * ASCII character, and its part of the slots.
     */
    private static function cost(int $states): int
    {
        return self::NUMBER_BYTES * ($states + 3) + 128 * self::STEP_BYTES + 128;
    }
}
