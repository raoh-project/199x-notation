<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Pattern;

/**
 * What a machine keeps changes how fast it walks and no answer. What a walk decides about keeping
 * sets is its own, so no subject leaves a later walk slower: a walk that is frozen goes back to
 * kept steps where it comes to a kept set and keeps nothing more, and the next walk keeps sets
 * again. Only makeRoom forgets the kept sets, a step that finds no room is decided there as a set
 * is, and a set larger than the room is kept beside the one a walk starts in. What is charged is
 * what PHP holds the kept sets in. Sets that share a hash are told apart, and the two loops that
 * move states take the same steps.
 */
final class MachineTest extends TestCase
{
    private int $rng = 7;

    /**
     * The machine of $pattern, read and walked once.
     */
    private static function machineOf(string $pattern): Machine
    {
        $read = Pattern::read($pattern);
        self::assertInstanceOf(Pattern::class, $read);
        $read->matches('');
        $machine = (new \ReflectionProperty(Pattern::class, 'machine'))->getValue($read);
        self::assertInstanceOf(Machine::class, $machine);
        return $machine;
    }

    /**
     * The machine of (?:a|b)*a(?:a|b){16}, whose deterministic sets are as many as 2^17, so that
     * random subjects come to a new set at most characters and fill the room.
     */
    private static function machine(): Machine
    {
        return self::machineOf('(?:a|b)*a(?:a|b){16}');
    }

    private function random(int $n): string
    {
        $out = '';
        for ($i = 0; $i < $n; $i++) {
            $this->rng = ($this->rng * 1664525 + 1013904223) & 0xFFFFFFFF;
            $out .= 'ab'[$this->rng >> 31];
        }
        return $out;
    }

    private static function get(Machine $m, string $name): mixed
    {
        return (new \ReflectionProperty(Machine::class, $name))->getValue($m);
    }

    /** How many sets $m keeps. */
    private static function kept(Machine $m): int
    {
        $states = self::get($m, 'keptStates');
        self::assertIsArray($states);
        return count($states);
    }

    /** How many steps $m keeps from its kept sets. */
    private static function steps(Machine $m): int
    {
        $next = self::get($m, 'keptNext');
        self::assertIsArray($next);
        $steps = 0;
        foreach ($next as $from) {
            self::assertIsArray($from);
            $steps += count($from);
        }
        return $steps;
    }

    private static function check(Machine $m, string $subject): void
    {
        $want = strlen($subject) >= 17 && $subject[strlen($subject) - 17] === 'a';
        self::assertSame($want, $m->matches($subject), substr($subject, 0, 20));
    }

    private static function constant(string $name): int
    {
        $value = (new \ReflectionClassConstant(Machine::class, $name))->getValue();
        self::assertIsInt($value);
        return $value;
    }

    /**
     * A machine holds the sets and steps it has kept and the room they take, and nothing of how
     * the walks before went: no count of what they read or made, and nothing that says whether
     * to keep sets, which a subject could leave behind to slow every walk after it.
     */
    public function testAMachineHoldsWhatIsKeptAndNothingOfHowWalksWent(): void
    {
        $names = [];
        foreach ((new \ReflectionClass(Machine::class))->getProperties() as $property) {
            if (!$property->isStatic()) {
                $names[] = $property->getName();
            }
        }
        sort($names);
        $kept = ['bytes', 'first', 'floor', 'keptAccepts', 'keptHashes', 'keptLoop', 'keptNext', 'keptStates', 'slots'];
        $machine = ['accept', 'freeStart', 'freeTo', 'setRanges', 'setStart', 'sets', 'stepOver', 'stepStart', 'stepTo'];
        $all = array_merge($kept, $machine);
        sort($all);
        self::assertSame($all, $names);
    }

    /**
     * A match of random subjects fills the room twice and is frozen, and the match after it, of a
     * subject that comes to few sets, keeps its sets all the same: it forgets what the frozen one
     * left and keeps its own, and the same subject again is read by the steps kept, keeping
     * nothing more.
     */
    public function testAMatchAfterAFrozenOneKeepsSetsAgain(): void
    {
        $m = self::machine();
        self::check($m, $this->random(20_000));
        $friendly = str_repeat('ab', 400);
        // The first forgets what the frozen match left, partway, and the steps from the set it
        // started in with it; the second works out again what the first forgot.
        self::check($m, $friendly);
        self::check($m, $friendly);
        $sets = self::kept($m);
        self::assertLessThan(40, $sets, 'the sets the frozen match left were not forgotten');
        $steps = self::steps($m);
        $bytes = self::get($m, 'bytes');
        for ($i = 0; $i < 3; $i++) {
            self::check($m, $friendly);
        }
        self::assertSame($sets, self::kept($m), 'the same subject kept a new set');
        self::assertSame($steps, self::steps($m), 'the same subject kept a new step');
        self::assertSame($bytes, self::get($m, 'bytes'));
        // And a random subject after it is no slower to come back from.
        self::check($m, $this->random(20_000));
        self::check($m, $friendly);
        self::check($m, $friendly);
        self::assertSame($sets, self::kept($m));
    }

    /**
     * A frozen match keeps no set and no step, so what is kept is never more than the room, and
     * a match of random subjects leaves no more kept than one that fills the room once would.
     */
    public function testAFrozenMatchKeepsNothingMore(): void
    {
        $was = Machine::$knownBytes;
        Machine::$knownBytes = 1 << 16;
        try {
            $m = self::machine();
            for ($i = 0; $i < 5; $i++) {
                self::check($m, $this->random(5_000));
                self::assertLessThanOrEqual(1 << 16, self::get($m, 'bytes'));
            }
            // Frozen, a set not kept is not kept, and its hash is not looked for in vain twice.
            $settle = new \ReflectionMethod(Machine::class, 'settle');
            $mode = self::constant('FROZEN');
            $made = 0;
            $read = 0;
            $sets = self::kept($m);
            $none = [999_999 => true];
            $args = [$none, &$mode, &$made, &$read];
            self::assertSame([-1, false], $settle->invokeArgs($m, $args));
            self::assertSame($sets, self::kept($m));
            self::assertSame(self::constant('FROZEN'), $mode);
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * A frozen walk, going a state at a time, takes kept steps again where it comes to a kept set:
     * the set it is in is looked up among those kept after each step, and found.
     */
    public function testAFrozenWalkGoesBackToKeptStepsWhereItComesToAKeptSet(): void
    {
        $m = self::machine();
        self::check($m, str_repeat('b', 40));
        $next = self::get($m, 'keptNext');
        self::assertIsArray($next);
        self::assertIsArray($next[0]);
        $loop = $next[0]['b'];
        self::assertIsInt($loop);
        self::assertIsArray($next[$loop]);
        self::assertSame($loop, $next[$loop]['b'], 'b does not lead the set b leads to back to itself');
        $states = self::get($m, 'keptStates');
        self::assertIsArray($states);
        self::assertIsArray($states[$loop]);
        // That set, held as a frozen walk outside the kept sets holds it; b leads it back to
        // itself, which is kept.
        $now = [];
        foreach ($states[$loop] as $q) {
            self::assertIsInt($q);
            $now[$q] = true;
        }
        $take = new \ReflectionMethod(Machine::class, 'take');
        $in = -1;
        $at = 0;
        $mode = self::constant('FROZEN');
        $made = 0;
        $read = 0;
        $steps = self::steps($m);
        $args = [&$in, &$now, 'b', &$at, &$mode, &$made, &$read];
        self::assertTrue($take->invokeArgs($m, $args));
        self::assertSame($loop, $in, 'the frozen walk did not find the kept set it came to');
        self::assertSame(1, $at);
        self::assertSame($steps, self::steps($m), 'the frozen walk kept a step');
    }

    /**
     * A set that alone takes more than the room is kept, with no other but the set a walk starts
     * in: (?:x*){124998} starts in a set of half the machine and comes to the same set of nearly
     * the whole of it at every x. Both are kept, and the steps between them, so a long run of x is
     * gone past at once, and a walk after is as many lookups as it reads characters.
     */
    public function testASetLargerThanTheRoomIsKeptBesideTheStart(): void
    {
        $m = self::machineOf('(?:x*){124998}');
        self::assertTrue($m->matches('xx'));
        self::assertTrue($m->matches('x'));
        self::assertSame(2, self::kept($m));
        self::assertSame(0, self::get($m, 'first'));
        self::assertGreaterThan(Machine::$knownBytes, self::get($m, 'bytes'));
        $next = self::get($m, 'keptNext');
        $loops = self::get($m, 'keptLoop');
        self::assertSame([['x' => 1], ['x' => 1]], $next);
        self::assertSame(['', 'x'], $loops);
        self::assertTrue($m->matches(str_repeat('x', 100_000)));
        self::assertTrue($m->matches(''));
        self::assertSame([['x' => 1], ['x' => 1]], self::get($m, 'keptNext'), 'a walk after kept a step');
        // A subject that leaves the two forgets them for the set it comes to, and keeps the start.
        self::assertFalse($m->matches(str_repeat('x', 1000) . 'y'));
        self::assertSame(0, self::get($m, 'first'));
        self::assertTrue($m->matches(str_repeat('x', 1000)));
    }

    /**
     * The kept sets are forgotten in one place, where a walk decides what to do with no room
     * (makeRoom), which counts every forgetting a walk causes. A forget called anywhere else would
     * be one the walk does not count, and nothing would bound what the walk spends keeping sets.
     */
    public function testOnlyMakeRoomForgetsTheKeptSets(): void
    {
        $forgetting = [];
        foreach ((new \ReflectionClass(Machine::class))->getMethods() as $method) {
            $file = $method->getFileName();
            $start = $method->getStartLine();
            $end = $method->getEndLine();
            self::assertIsString($file);
            self::assertIsInt($start);
            self::assertIsInt($end);
            $lines = file($file);
            self::assertIsArray($lines);
            $body = implode('', array_slice($lines, $start, $end - $start));
            if (str_contains($body, '$this->forget()')) {
                $forgetting[] = $method->getName();
            }
        }
        self::assertSame(['makeRoom'], $forgetting);
    }

    /**
     * A step that finds no room is decided as a set that finds none is: the walk forgets the kept
     * sets, the first time, and keeps the step after, so the same subject read again goes by kept
     * steps over every character. Before, a step over a character past ASCII that found no room
     * was left out without the walk knowing, and every walk after worked it out again, a state at a
     * time, for as long as the room stayed full.
     */
    public function testAStepThatFindsNoRoomIsDecidedAsASetIs(): void
    {
        $was = Machine::$knownBytes;
        Machine::$knownBytes = 1 << 30;
        try {
            $m = self::machineOf('(?:a|é)*');
            $subject = 'aéaéé';
            self::assertTrue($m->matches($subject));
            // The sets the subject comes to are kept and their steps are not, and the room is full.
            $next = self::get($m, 'keptNext');
            $loops = self::get($m, 'keptLoop');
            self::assertIsArray($next);
            self::assertIsArray($loops);
            (new \ReflectionProperty(Machine::class, 'keptNext'))->setValue($m, array_fill(0, count($next), []));
            (new \ReflectionProperty(Machine::class, 'keptLoop'))->setValue($m, array_fill(0, count($loops), ''));
            $bytes = self::get($m, 'bytes');
            self::assertIsInt($bytes);
            Machine::$knownBytes = $bytes;
            for ($i = 0; $i < 3; $i++) {
                self::assertTrue($m->matches($subject));
            }
            $in = self::get($m, 'first');
            $next = self::get($m, 'keptNext');
            self::assertIsArray($next);
            foreach (['a', 'é', 'a', 'é', 'é'] as $at => $character) {
                self::assertIsInt($in);
                self::assertIsArray($next[$in]);
                self::assertArrayHasKey($character, $next[$in], "character $at, $character, is not read by a kept step");
                $in = $next[$in][$character];
            }
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * What the kept sets are charged is what PHP holds them in, as memory_get_usage() counts it,
     * and no less: sets of many states and of few, steps over ASCII and past it, tables of steps
     * that double, and the slots and lists that grow with the sets kept. What they hold is what
     * PHP gives back when they are let go. What is charged is more only by what is charged once at
     * the most it grows to, a set's loop, or before it is made, the table of the steps of a set no
     * step has been taken from yet, which holds it to at most a tenth more than what is held.
     */
    public function testWhatIsChargedIsWhatPhpHoldsTheKeptSetsIn(): void
    {
        $was = Machine::$knownBytes;
        Machine::$knownBytes = 1 << 30;
        $distinct = '';
        for ($c = 0x100; $c < 0x300; $c++) {
            $distinct .= chr(0xC0 | ($c >> 6)) . chr(0x80 | ($c & 0x3F));
        }
        $cases = [
            '(?:a|b)*a(?:a|b){10}' => $this->random(2_000),
            'a{0,3000}' => str_repeat('a', 2_000),
            '(?:.)*' => $distinct . $distinct,
            '(?:[a-z]|é|ü)*a(?:[a-z]|é){3}' => str_repeat('abéüxyz', 300),
        ];
        try {
            foreach ($cases as $pattern => $subject) {
                $m = self::machineOf($pattern);
                $m->matches($subject);
                $charged = self::get($m, 'bytes');
                self::assertIsInt($charged);
                gc_collect_cycles();
                $before = memory_get_usage();
                foreach (['slots', 'keptStates', 'keptHashes', 'keptAccepts', 'keptNext', 'keptLoop'] as $name) {
                    (new \ReflectionProperty(Machine::class, $name))->setValue($m, []);
                }
                $held = $before - memory_get_usage();
                self::assertGreaterThan(0, $held, $pattern);
                self::assertGreaterThanOrEqual($held, $charged, "$pattern holds more than it was charged");
                self::assertLessThanOrEqual(intdiv(11 * $held, 10), $charged, "$pattern was charged more than it holds");
            }
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * Two sets with the same hash are told apart by their states, so a hash two sets share changes
     * no answer: {1, 1352} and {4, 1349} sum to the same hash, and each is kept as a set of its
     * own and found again as itself, whichever was kept first and in whatever order its states
     * were entered.
     */
    public function testSetsWithTheSameHashAreToldApart(): void
    {
        $read = Pattern::read('a{1400}');
        self::assertInstanceOf(Pattern::class, $read);
        $read->matches('');
        $m = (new \ReflectionProperty(Pattern::class, 'machine'))->getValue($read);
        self::assertInstanceOf(Machine::class, $m);
        $hashOf = new \ReflectionMethod(Machine::class, 'hashOf');
        $find = new \ReflectionMethod(Machine::class, 'find');
        $keep = new \ReflectionMethod(Machine::class, 'keep');
        $one = [1 => true, 1352 => true];
        $other = [4 => true, 1349 => true];
        $hash = $hashOf->invoke($m, $one);
        self::assertSame($hash, $hashOf->invoke($m, $other), 'the two sets do not share a hash');
        $kept = $keep->invoke($m, $one, $hash, false);
        self::assertIsInt($kept);
        self::assertGreaterThanOrEqual(0, $kept);
        self::assertSame(-1, $find->invoke($m, $other, $hash), '{4, 1349} is taken for {1, 1352}');
        $beside = $keep->invoke($m, $other, $hash, false);
        self::assertIsInt($beside);
        self::assertGreaterThanOrEqual(0, $beside);
        self::assertNotSame($kept, $beside);
        self::assertSame($kept, $find->invoke($m, $one, $hash));
        self::assertSame($beside, $find->invoke($m, $other, $hash));
        self::assertSame($kept, $find->invoke($m, [1352 => true, 1 => true], $hash));
        self::assertSame($beside, $find->invoke($m, [1349 => true, 4 => true], $hash));
    }

    /**
     * advanceStates and advanceSet take the same steps, and differ only in how they go over the
     * states they are handed: a list of them, or a set keyed by them. Each is written out for
     * speed, so this holds the one to the other.
     */
    public function testBothAdvancesTakeTheSameSteps(): void
    {
        $bodies = [];
        foreach (['advanceStates', 'advanceSet'] as $name) {
            $method = new \ReflectionMethod(Machine::class, $name);
            $file = $method->getFileName();
            self::assertIsString($file);
            $lines = file($file);
            self::assertIsArray($lines);
            $start = $method->getStartLine();
            $end = $method->getEndLine();
            self::assertIsInt($start);
            self::assertIsInt($end);
            $body = array_slice($lines, $start, $end - $start);
            $bodies[$name] = array_values(array_filter($body, static fn (string $line): bool => !str_contains($line, 'foreach ($from as')));
        }
        self::assertSame($bodies['advanceStates'], $bodies['advanceSet']);
        // And they answer alike, over a set held both ways.
        $read = Pattern::read('(?:a|b|é)*a(?:[ab]|é){3}');
        self::assertInstanceOf(Pattern::class, $read);
        $read->matches('');
        $m = (new \ReflectionProperty(Pattern::class, 'machine'))->getValue($read);
        self::assertInstanceOf(Machine::class, $m);
        $states = (new \ReflectionProperty(Machine::class, 'keptStates'))->getValue($m);
        self::assertIsArray($states);
        $first = $states[0];
        self::assertIsArray($first);
        foreach ([0x61, 0x62, 0xE9, 0x63] as $symbol) {
            $set = [];
            foreach ($first as $q) {
                self::assertIsInt($q);
                $set[$q] = true;
            }
            self::assertSame(
                (new \ReflectionMethod(Machine::class, 'advanceStates'))->invoke($m, $first, $symbol),
                (new \ReflectionMethod(Machine::class, 'advanceSet'))->invoke($m, $set, $symbol),
            );
        }
    }
}
