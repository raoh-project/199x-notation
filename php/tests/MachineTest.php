<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Pattern;

/**
 * A machine that gave up keeping sets tries again, as Go's and Rust's do: a pattern may be held for
 * long, and one that gave up for good would walk every later subject a state at a time.
 */
final class MachineTest extends TestCase
{
    private int $rng = 7;

    /**
     * The machine of (?:a|b)*a(?:a|b){16}, whose deterministic sets are as many as 2^17, so that
     * random subjects make it give up keeping them.
     */
    private static function machine(): Machine
    {
        $read = Pattern::read('(?:a|b)*a(?:a|b){16}');
        self::assertInstanceOf(Pattern::class, $read);
        $read->matches('');
        $machine = (new \ReflectionProperty(Pattern::class, 'machine'))->getValue($read);
        self::assertInstanceOf(Machine::class, $machine);
        return $machine;
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

    private static function get(Machine $m, string $name): int|bool
    {
        $value = (new \ReflectionProperty(Machine::class, $name))->getValue($m);
        self::assertTrue(is_int($value) || is_bool($value));
        return $value;
    }

    private static function set(Machine $m, string $name, int $value): void
    {
        (new \ReflectionProperty(Machine::class, $name))->setValue($m, $value);
    }

    private static function check(Machine $m, string $subject): void
    {
        $want = strlen($subject) >= 17 && $subject[strlen($subject) - 17] === 'a';
        self::assertSame($want, $m->matches($subject), substr($subject, 0, 20));
    }

    private function untilGivenUp(Machine $m): void
    {
        while (self::get($m, 'off') === false) {
            self::check($m, $this->random(20_000));
        }
    }

    /**
     * It tries again once it has walked what it waits for, keeps sets for subjects whose sets are
     * looked up again, and waits twice as long after a try that gives up again; the answers are
     * the same throughout.
     */
    public function testAGivenUpMachineTriesAgainAndWaitsLongerAfterEachTryThatFails(): void
    {
        $m = self::machine();
        $this->untilGivenUp($m);
        self::assertSame(0, self::get($m, 'offFor'), 'the first give-up waits $retryWork');
        // Waited for, so that the test walks less than the constant says. A walk of 800 bytes
        // walks 801, as $retryWork counts, and what has been walked is asked before a walk.
        self::set($m, 'offFor', 1000);
        self::set($m, 'offWork', 0);
        self::check($m, str_repeat('ab', 400));
        self::check($m, str_repeat('ab', 400));
        self::assertTrue(self::get($m, 'off'), 'it tried again before it walked what it waits for');
        self::check($m, str_repeat('ab', 400));
        self::assertFalse(self::get($m, 'off'), 'it did not try again once it had');
        $this->untilGivenUp($m);
        self::assertSame(2000, self::get($m, 'offFor'), 'a try that gave up again waits twice as long');
        self::set($m, 'offWork', 2000);
        for ($i = 0; $i < 1000; $i++) {
            self::check($m, str_repeat('ab', 20));
        }
        self::assertFalse(self::get($m, 'off'), 'subjects whose sets are looked up again made it give up');
    }

    /**
     * What a walk without kept sets walks is what counts toward trying again: an empty subject
     * counts the set it starts in, so empty subjects alone lead to a try, and a long subject turned
     * away at once counts the little that was walked of it, not its length.
     */
    public function testWhatCountsTowardTryingAgainIsWhatWasWalked(): void
    {
        $m = self::machine();
        $this->untilGivenUp($m);
        self::set($m, 'offFor', 100);
        self::set($m, 'offWork', 0);
        for ($i = 0; $i < 100; $i++) {
            self::assertTrue(self::get($m, 'off'), 'empty subjects led to a try too soon');
            self::assertFalse($m->matches(''));
        }
        $m->matches('');
        self::assertFalse(self::get($m, 'off'), 'empty subjects alone did not lead to a try');
        $this->untilGivenUp($m);
        $before = self::get($m, 'offWork');
        self::assertIsInt($before);
        self::assertFalse($m->matches('c' . str_repeat('a', 100_000)));
        self::assertLessThanOrEqual(3, self::get($m, 'offWork') - $before, 'a subject turned away at once counted its length');
    }

    /**
     * A walk that gives up keeping sets part of the way through counts what it walks after, as one
     * that had given up before it does: the wait before trying again bounds every walk without kept
     * sets, wherever it began.
     */
    public function testAWalkThatGivesUpOnItsWayCountsWhatItWalksAfter(): void
    {
        $m = self::machine();
        $this->untilGivenUp($m);
        self::assertGreaterThan(1000, self::get($m, 'offWork'));
    }

    /**
     * A count that only grows is held at the most an int holds, and a wait doubled past it is that.
     */
    public function testCountsThatOnlyGrowAreHeldAtTheMostAnIntHolds(): void
    {
        self::assertSame(PHP_INT_MAX, Machine::grown(PHP_INT_MAX - 1, 5));
        self::assertSame(5, Machine::grown(2, 3));
        $m = self::machine();
        self::set($m, 'offFor', intdiv(PHP_INT_MAX, 2) + 1);
        (new \ReflectionProperty(Machine::class, 'retrying'))->setValue($m, true);
        (new \ReflectionMethod(Machine::class, 'giveUp'))->invoke($m);
        self::assertSame(PHP_INT_MAX, self::get($m, 'offFor'));
    }
}
