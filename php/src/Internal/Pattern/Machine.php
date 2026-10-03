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
 * What the sets kept do to how fast a walk goes is held to three promises. A walk spends on keeping
 * sets no more than what fills the room twice, unless keeping them pays for itself: the kept sets
 * are forgotten in one place, makeRoom, which counts each forgetting a walk causes, and nothing of
 * what one walk decided is held for the next. Whatever does not fit, a set or a step, is decided
 * there, and is never left out without the walk knowing. The sets every walk needs, the set it
 * starts in and the set it is in when the others are forgotten for it, are kept whatever they take,
 * and are not charged against the room (need): the room bounds everything else, the other sets,
 * the steps and the lists that hold them, so what is kept is at most the room and those two sets,
 * at most twice the machine's states. And what is kept is held to that by charging, as it is made,
 * what PHP takes to hold it (charge, need), which MachineTest holds to what memory_get_usage()
 * counts.
 *
 * @internal
 */
final class Machine
{
    /**
     * How much the sets a machine has worked out, and the steps between them, may take, in bytes,
     * before a walk that comes to one more forgets them or keeps no more (makeRoom). The sets every
     * walk needs are kept whatever they take, beside the room and not in it (need).
     */
    public static int $knownBytes = 2 << 20;

    /** A walk keeps the sets it comes to, and has not forgotten the sets kept before it. */
    private const KEEPING = 0;
    /** A walk keeps the sets it comes to, and has forgotten the sets kept before it at least once. */
    private const KEEPING_AGAIN = 1;
    /** A walk keeps no more sets, and looks up among those kept each set it comes to. */
    private const FROZEN = 2;

    // What PHP 8 takes on a 64-bit platform for what the kept sets are held in, charged as it is
    // made (charge). An array is a zend_array of 56 bytes and room for a power of two of places,
    // eight at least, which it doubles into as it fills. A list's place is a zval of 16 bytes, with
    // 8 bytes of hash for the list as a whole; a table keyed by strings has a bucket of 32 bytes and
    // two 4-byte hash slots for each place. A key of more than one byte is a string of its own, of
    // 24 bytes and the key and its end; a one-byte key is one PHP holds once for every program.
    // PHP's allocator gives each of these the least of its sizes that holds it (bin), and that is
    // what is charged. MachineTest holds what is charged to what PHP says it took.

    /** The zend_array an array is before it holds anything. */
    private const ARRAY_BYTES = 56;
    /** What a list takes for each place, and what it takes besides its places. */
    private const LIST_PLACE_BYTES = 16;
    private const LIST_HASH_BYTES = 8;
    /** What a table keyed by strings takes for each place. */
    private const TABLE_PLACE_BYTES = 40;
    /**
     * What a set's table of steps takes once its first step is kept: eight places, 320 bytes, one
     * of the allocator's sizes. It is charged with the set (keep), since every set a walk keeps
     * but the last it comes to has a step taken from it.
     */
    private const TABLE_BYTES = self::ARRAY_BYTES + self::TABLE_PLACE_BYTES * self::SMALLEST;
    /** What a key of two to four bytes takes: a string of 24 bytes, the key and its end, in the 32-byte bin. */
    private const KEY_BYTES = 32;
    /** The most a set's loop takes: each ASCII character once in a string, 153 bytes in the 160-byte bin. */
    private const LOOP_BYTES = 160;
    /** The fewest places PHP makes an array with. */
    private const SMALLEST = 8;
    /** How many lists have one place for each kept set: its states, hash, whether it accepts, next and loop. */
    private const LISTS = 5;

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
    /** What the kept sets and the steps between them take, as charge() charges it: the room's. */
    private int $bytes = 0;
    /** What the sets every walk needs take, as need() counts it, beside the room. */
    private int $needed = 0;

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
     * has forgotten the sets kept before it, whether it keeps any more, and the characters it
     * worked out a state at a time to keep and those it read by kept steps since it last forgot
     * them ($mode, $worked and $read here).
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
        $worked = 0;
        $read = 0;
        $in = $this->begin($now);
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
            if (!$this->take($in, $now, $subject, $at, $mode, $worked, $read)) {
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
     * it: the kept set it starts in, kept now where it was not. Starting the kept sets again keeps
     * this one (afresh), so it is kept here only by the first walk of a machine, through afresh,
     * as one of the sets every walk needs: this is not a walk deciding to forget anything, and is
     * not counted as one.
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
        $this->first = $this->afresh($now, $this->hashOf($now), '', 0, false);
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
    private function take(int &$in, array &$now, string $subject, int &$at, int &$mode, int &$worked, int &$read): bool
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
            [$in] = $this->settle($now, $mode, $worked, $read, $character, $width, false);
            return $in >= 0 ? $this->keptStates[$in] !== [] : $now !== [];
        }
        // What is read by kept steps is counted where it is read (matches); a step worked out here
        // is not one, so it does not count toward keeping the sets paying for itself.
        $from = $in;
        $fromStart = $from === $this->first;
        $now = $this->advanceStates($this->keptStates[$from], $symbol);
        if ($mode !== self::FROZEN) {
            $worked++;
        }
        [$next, $forgot] = $this->settle($now, $mode, $worked, $read, $character, $width, $fromStart);
        // A frozen walk adds no step to the kept sets. A step is kept where there is room for it,
        // and where there is none the walk decides as it does for a set (makeRoom): it starts the
        // kept sets again with the set it came to, $from forgotten with the others, or it keeps no
        // more and goes on from the set it came to, which is kept. Where the sets were started
        // again, $from is not looked up again, but for the set a walk starts in, which starting
        // them again keeps with the step from it (afresh).
        if (!$forgot && $next >= 0 && $mode !== self::FROZEN
            && !$this->keepStep($from, $character, $width, $next, false)) {
            $again = $this->makeRoom($mode, $worked, $read, $now, $this->hashOf($now), $character, $width, $fromStart);
            if ($again >= 0) {
                $next = $again;
            }
        }
        $in = $next;
        if ($next >= 0) {
            return $this->keptStates[$next] !== [];
        }
        return $now !== [];
    }

    /**
     * Keeps that $character, of $width bytes, leads from the kept set $from to the kept set $next,
     * and is true; or is false, keeping nothing, where there is no room for it. With $beside, it is
     * kept whatever it takes, beside the room (need), which only afresh asks, for the step from the
     * set a walk starts in to the set it is in when the others were just forgotten for it.
     *
     * What keeping the step makes past the table $from was charged with (TABLE_BYTES): twice the
     * table's places, where it is full; the key, where it is more than a byte; and the loop of
     * $from, where this is the first character found to lead $from back to itself, charged once at
     * the most it grows to.
     */
    private function keepStep(int $from, string $character, int $width, int $next, bool $beside): bool
    {
        $steps = count($this->keptNext[$from]);
        $cost = 0;
        if ($steps >= self::SMALLEST && ($steps & ($steps - 1)) === 0) {
            $cost = self::bin(self::TABLE_PLACE_BYTES * 2 * $steps) - self::bin(self::TABLE_PLACE_BYTES * $steps);
        }
        $loop = $width === 1 && $next === $from;
        if ($width > 1) {
            $cost += self::KEY_BYTES;
        } elseif ($loop && $this->keptLoop[$from] === '') {
            $cost += self::LOOP_BYTES;
        }
        if ($beside) {
            $this->need($cost);
        } elseif ($cost > 0 && !$this->charge($cost)) {
            return false;
        }
        $this->keptNext[$from][$character] = $next;
        if ($loop) {
            $this->keptLoop[$from] .= $character;
        }
        return true;
    }

    /**
     * The kept set $now holds, and whether the sets kept before were forgotten to make room for
     * it: found among those kept, or kept now, or -1 where the walk is frozen and it is not kept.
     * Where there is no room for it, the walk decides what to do (makeRoom), and keep() only says
     * so. A frozen walk looks each set it comes to up among those kept, so that it takes kept
     * steps again where it comes to one. $character, of $width bytes, is what the walk came to it
     * over, from the set a walk starts in where $fromStart.
     *
     * @param array<int, true> $now
     * @return array{int, bool}
     */
    private function settle(array $now, int &$mode, int &$worked, int &$read, string $character, int $width, bool $fromStart): array
    {
        $hash = $this->hashOf($now);
        $set = $this->find($now, $hash);
        if ($set >= 0 || $mode === self::FROZEN) {
            return [$set, false];
        }
        $set = $this->keep($now, $hash, false);
        if ($set >= 0) {
            return [$set, false];
        }
        $set = $this->makeRoom($mode, $worked, $read, $now, $hash, $character, $width, $fromStart);
        return [$set, $set >= 0];
    }

    /**
     * What a walk does where there is no room for a set or a step it comes to, the set $now holds,
     * whose hash is $hash: it starts the kept sets again with that set (afresh) and is where it is
     * kept, or it keeps no more for the rest of the subject, and is -1. This is the one place the
     * kept sets are forgotten and the one place a walk is frozen, and every forgetting a walk
     * causes is counted here, so that what a walk spends keeping sets is bounded whatever it reads.
     *
     * The first time in a walk, the sets are forgotten without asking anything: they were kept by
     * walks before this one, and what they cost says nothing about the subject read now. After
     * that, they are forgotten again only where the walk read by kept steps at least ten characters
     * for each it worked out a state at a time to keep since it last forgot them, whether that came
     * to a new set or to a kept one by a new step, so that keeping them paid for itself. Counted in
     * sets made, a walk whose new steps led only to kept sets, and filled the room with steps,
     * counted nothing it had worked out, was never frozen, and started the kept sets again each
     * time it filled the room.
     * Otherwise keeping them saves nothing, and the walk is frozen: it keeps no more sets and no
     * more steps, so what it holds grows no further, and it goes a state at a time, looking up each
     * set it comes to among those kept. A walk that keeps coming to new sets so spends on keeping
     * them at most what fills the room twice. The next walk starts keeping sets again: nothing of
     * what a walk decided is held by the machine.
     *
     * @param array<int, true> $now
     */
    private function makeRoom(int &$mode, int &$worked, int &$read, array $now, int $hash, string $character, int $width, bool $fromStart): int
    {
        if ($mode === self::KEEPING_AGAIN && $read < 10 * $worked) {
            $mode = self::FROZEN;
            return -1;
        }
        $mode = self::KEEPING_AGAIN;
        $worked = 0;
        $read = 0;
        return $this->afresh($now, $hash, $character, $width, $fromStart);
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
     * (makeRoom); this only holds the sets to the room. What is charged is what keeping it makes:
     * the list of its states, the table of its steps (TABLE_BYTES), a place in each of the lists
     * that hold one for every kept set, as they double, and twice the slots, where they double.
     *
     * With $beside, the set is kept whatever it takes, beside the room (need), which only afresh
     * asks, for the sets every walk needs: the set a walk starts in, and the set a walk is in just
     * after the others were forgotten for it. So a set that alone takes more than $knownBytes is kept
     * beside the one a walk starts in, and is not walked a state at a time at every character that
     * comes to it again, as (?:x*){124998} comes to the same set of nearly the whole machine at
     * every x; and since it takes none of the room, the steps from it, over ASCII or past it, are
     * kept in the room as any are.
     *
     * @param array<int, true> $now
     */
    private function keep(array $now, int $hash, bool $beside): int
    {
        $set = count($this->keptStates);
        $cost = self::listBytes(count($now)) + self::TABLE_BYTES;
        // The lists with a place for each kept set are made with the first and double when full.
        if ($set === 0) {
            $cost += self::LISTS * self::listBytes(1);
        } elseif ($set >= self::SMALLEST && ($set & ($set - 1)) === 0) {
            $cost += self::LISTS * (self::listBytes(2 * $set) - self::listBytes($set));
        }
        $grow = ($set + 1) * 2 > count($this->slots);
        if ($grow) {
            $cost += self::listBytes(max(16, 2 * count($this->slots))) - self::listBytes(count($this->slots));
        }
        if ($beside) {
            $this->need($cost);
        } elseif (!$this->charge($cost)) {
            return -1;
        }
        if ($grow) {
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
     * Takes $bytes of the room the kept sets have, $knownBytes, and is false, taking nothing,
     * where there is not that much left. Every set and every step the kept sets hold is charged
     * here or in need(), as it is made, which is what holds them to the room.
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
     * Counts $bytes made for what every walk needs, beside the room and taking none of it: the
     * set a walk starts in, the set a walk is in just after the others were forgotten for it, and
     * the step between them. The two sets are each at most the machine's states, so what is held
     * past the room is bounded by the machine and not by what is read. Charged in the room, a set
     * larger than it would leave no room for any step from it, and a walk that goes round it would
     * be frozen on its first step back to it.
     */
    private function need(int $bytes): void
    {
        $this->needed += $bytes;
    }

    /**
     * Starts the kept sets again from what every walk needs, and is where the set $now holds, whose
     * hash is $hash, is kept. It forgets every set kept but the one
     * a walk starts in, which is kept again as the first, and every step; keeps the set $now holds
     * beside it, whatever it takes; and keeps the step over $character, of $width bytes, from the
     * first to it where $fromStart. A machine that has kept nothing keeps the set $now holds as the
     * one a walk starts in. A number a walk holds names no set afterwards, but for the first.
     *
     * It is the one way anything is kept beside the room (need), so what is beside it is those two
     * sets and that step, whoever calls it and however often, and not something its callers keep
     * to by the order they call in. Only makeRoom and begin call it.
     *
     * The set a walk starts in is every walk's, so it is not left for the next walk to work out
     * again, as RE2 keeps its start when it forgets its states. (?:x*){124998} starts in a set of
     * half the machine and goes round one of the whole of it, which is kept by forgetting the
     * others, with the step from the start to it, so the next walk takes that step as one lookup.
     *
     * @param array<int, true> $now
     */
    private function afresh(array $now, int $hash, string $character, int $width, bool $fromStart): int
    {
        $first = $this->first;
        $states = $first >= 0 ? $this->keptStates[$first] : [];
        $firstHash = $first >= 0 ? $this->keptHashes[$first] : 0;
        $accepts = $first >= 0 && $this->keptAccepts[$first];
        $this->slots = [];
        $this->keptStates = [];
        $this->keptHashes = [];
        $this->keptAccepts = [];
        $this->keptNext = [];
        $this->keptLoop = [];
        $this->first = -1;
        $this->bytes = 0;
        $this->needed = 0;
        if ($first < 0) {
            $this->first = $this->keep($now, $hash, true);
            return $this->first;
        }
        $this->keptStates[] = $states;
        $this->keptHashes[] = $firstHash;
        $this->keptAccepts[] = $accepts;
        $this->keptNext[] = [];
        $this->keptLoop[] = '';
        $this->grow();
        $this->slots[$this->free($firstHash)] = 0;
        $this->first = 0;
        $this->need(self::listBytes(count($states)) + self::TABLE_BYTES + self::LISTS * self::listBytes(1) + self::listBytes(count($this->slots)));
        $set = $this->find($now, $hash);
        if ($set < 0) {
            $set = $this->keep($now, $hash, true);
        }
        if ($fromStart) {
            $this->keepStep($this->first, $character, $width, $set, true);
        }
        return $set;
    }

    /**
     * What a list of $n numbers takes, or a list of $n places PHP makes at once: the zend_array,
     * and its hash and a place for each of the power of two it is made with or doubles to, eight at
     * least, as bin() gives them; nothing for an empty one, which PHP holds once for every program.
     *
     * The places are a power of two of bytes, and the hash puts them a little past it, so bin()
     * gives a quarter more for them up to 3072 bytes, and a page more past that. It is worked out
     * so here, without the calls, since a list of states is made for every set kept.
     */
    private static function listBytes(int $n): int
    {
        if ($n === 0) {
            return 0;
        }
        $p = max($n, self::SMALLEST) - 1;
        $p |= $p >> 1;
        $p |= $p >> 2;
        $p |= $p >> 4;
        $p |= $p >> 8;
        $p |= $p >> 16;
        $places = self::LIST_PLACE_BYTES * ($p + 1);
        return self::ARRAY_BYTES + ($places + self::LIST_HASH_BYTES <= 3072 ? $places + ($places >> 2) : $places + 4096);
    }

    /**
     * What PHP's allocator gives for $bytes: the least of its small sizes that holds them, which
     * go by eight up to 64 and then by four steps between each power of two and the next, up to
     * 3072; and past that, whole pages of 4096 bytes.
     */
    private static function bin(int $bytes): int
    {
        if ($bytes <= 64) {
            return ($bytes + 7) & ~7;
        }
        if ($bytes > 3072) {
            return ($bytes + 4095) & ~4095;
        }
        $step = self::places($bytes) >> 3;
        return ($bytes + $step - 1) & ~($step - 1);
    }

    /**
     * The places PHP makes an array of $n with, or has doubled it to once it holds $n: the least
     * power of two that is $n or more, and eight at least. $n is below 2^32, as many states as a
     * machine has or as many sets as are kept.
     */
    private static function places(int $n): int
    {
        $n = max($n, self::SMALLEST) - 1;
        $n |= $n >> 1;
        $n |= $n >> 2;
        $n |= $n >> 4;
        $n |= $n >> 8;
        $n |= $n >> 16;
        return $n + 1;
    }
}
