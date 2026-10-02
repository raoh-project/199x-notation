<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\Attributes\PreserveGlobalState;
use PHPUnit\Framework\Attributes\RunInSeparateProcess;
use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Pattern;
use Raoh\Notation199x\PatternBeyond;
use Raoh\Notation199x\PatternLimit;
use Raoh\Notation199x\PatternRefusal;
use Raoh\Notation199x\PatternRefused;
use Raoh\Notation199x\ScalarValues;

final class PatternTest extends TestCase
{
    private static function refusedOf(string $pattern): PatternRefused
    {
        $read = Pattern::read($pattern);
        self::assertInstanceOf(PatternRefused::class, $read, "$pattern is not refused");
        return $read;
    }

    private static function read(string $pattern): Pattern
    {
        $read = Pattern::read($pattern);
        self::assertInstanceOf(Pattern::class, $read, "$pattern is not read");
        return $read;
    }

    /**
     * What makes text no pattern is told apart by what an author wrote.
     */
    public function testEachRefusalIsForWhatWasWritten(): void
    {
        foreach ([
            '(a' => PatternRefusal::SomethingUnclosed,
            '[a' => PatternRefusal::SomethingUnclosed,
            'a)' => PatternRefusal::SomethingUnclosed,
            '*a' => PatternRefusal::SomethingUnclosed,
            'a{6,2}' => PatternRefusal::ACountThisCannotRead,
            'a{' => PatternRefusal::ACountThisCannotRead,
            '[b-a]' => PatternRefusal::ACountThisCannotRead,
            '\\y' => PatternRefusal::AnEscapeThisDoesNotRead,
            '\\x{110000}' => PatternRefusal::AnEscapeThisDoesNotRead,
            '[a-\\d]' => PatternRefusal::AnEscapeThisDoesNotRead,
            '\\꟝' => PatternRefusal::AnEscapeThisDoesNotRead,
            '(?=a)b' => PatternRefusal::AGroupTheGrammarDoesNotHave,
            '(?<name>a)' => PatternRefusal::AGroupTheGrammarDoesNotHave,
            '(?i)a' => PatternRefusal::AGroupTheGrammarDoesNotHave,
            '(a)\\1' => PatternRefusal::ABackReference,
            '\\k<a>' => PatternRefusal::ABackReference,
            '\\p{Alpha}' => PatternRefusal::ACharacterProperty,
            '\\bword\\b' => PatternRefusal::ABoundary,
            '\\Qa+b\\E' => PatternRefusal::AQuotation,
            '[a-z&&[^bc]]' => PatternRefusal::AClassOfClasses,
            '[a[bc]]' => PatternRefusal::AClassOfClasses,
            'a{2,6}+' => PatternRefusal::APossessiveRepetition,
            '(?:|a)++' => PatternRefusal::APossessiveRepetition,
            '(a|)^b' => PatternRefusal::AnAnchorThisCannotPlace,
            '(^a)*' => PatternRefusal::AnAnchorThisCannotPlace,
            'a$b' => PatternRefusal::AnAnchorThisCannotPlace,
        ] as $pattern => $why) {
            self::assertSame($why, self::refusedOf((string) $pattern)->why, (string) $pattern);
        }
    }

    /**
     * A pair of \u escapes is the one character it encodes, worked out before the character is
     * asked whether it is a symbol; half of a pair is no symbol however it is written, in a class
     * or out of one.
     */
    public function testAPairOfUnicodeEscapesIsOneCharacter(): void
    {
        foreach (['\\uD83D\\uDE00', '[\\uD83D\\uDE00]', '[\\uD83D\\uDE00-\\uD83D\\uDE4F]', '\\x{1F600}', "\u{1F600}"] as $pattern) {
            $read = self::read($pattern);
            self::assertTrue($read->matches("\u{1F600}"), $pattern);
            self::assertFalse($read->matches("\u{1F601}\u{1F601}"), $pattern);
        }
        foreach (['\\uD83D', '\\uDE00', '\\uDE00\\uD83D', '\\uD83DA', '\\uD83D\\uD83D', '\\x{D800}', '[\\uD800]', '[a-\\x{DFFF}]'] as $pattern) {
            self::assertSame(PatternRefusal::ACharacterNoStringHolds, self::refusedOf($pattern)->why, $pattern);
        }
        // A high escape with a malformed escape after it spells the high surrogate, and is refused
        // for that rather than for the escape after it.
        $refused = self::refusedOf('\\uD83D\\u00G0');
        self::assertSame(PatternRefusal::ACharacterNoStringHolds, $refused->why);
        self::assertSame(0, $refused->from);
    }

    /**
     * Bytes that are not UTF-8 are no character, wherever the reader takes a character from the
     * text: a refusal from where they are.
     */
    public function testBytesThatAreNotUtf8AreRefusedWhereverACharacterIsTaken(): void
    {
        foreach ([
            ['%s', 0], ['ab%scd', 2], ['(?:a|%s)', 5], ['%s+', 0], ['[%s]', 1], ['[^%s]', 2],
            ['[a%sb]', 2], ['[%s-z]', 1], ['[a-%s]', 1], ['\\%s', 0], ['[\\%s]', 1],
        ] as [$place, $at]) {
            foreach (["\xff", "\xed\xa0\x80", "\xc3"] as $bad) {
                $pattern = str_replace('%s', $bad, $place);
                $refused = self::refusedOf($pattern);
                self::assertSame(PatternRefusal::ACharacterNoStringHolds, $refused->why, bin2hex($pattern));
                self::assertSame($at, $refused->from, bin2hex($pattern));
            }
        }
    }

    /**
     * Where a refusal points and what it quotes are in bytes of the text, and quote characters
     * whole.
     */
    public function testARefusalQuotesTheConstructInBytes(): void
    {
        foreach ([
            ['é(?😀', new PatternRefused(PatternRefusal::AGroupTheGrammarDoesNotHave, 2, '(?😀')],
            ['éé\\p{L}', new PatternRefused(PatternRefusal::ACharacterProperty, 4, '\\p')],
            ['é{3,1}', new PatternRefused(PatternRefusal::ACountThisCannotRead, 2, '{3,1}')],
            ['é)', new PatternRefused(PatternRefusal::SomethingUnclosed, 2, ')')],
            ['(é', new PatternRefused(PatternRefusal::SomethingUnclosed, 3, '')],
            ['a(a|)^b', new PatternRefused(PatternRefusal::AnAnchorThisCannotPlace, 0, 'a(a|)^b')],
            ['[😀\\x{D800}]', new PatternRefused(PatternRefusal::ACharacterNoStringHolds, 5, '\\x{D800}')],
        ] as [$pattern, $want]) {
            self::assertEquals($want, self::refusedOf($pattern), $pattern);
        }
    }

    /**
     * A limit is noted where it is met and the reading goes on, so text that is no pattern after it
     * is refused, and the first limit met in the text is the answer.
     */
    public function testALimitIsAnsweredOnlyOfAPattern(): void
    {
        self::assertSame(PatternRefusal::SomethingUnclosed, self::refusedOf('a{134217728}(')->why);
        self::assertEquals(
            new PatternBeyond(PatternLimit::RepetitionCount, 3, '134217728'),
            Pattern::read('é{134217728}' . str_repeat('(', 201) . str_repeat(')', 201)),
        );
        $read = Pattern::read('a{134217727}');
        self::assertInstanceOf(PatternBeyond::class, $read, 'a{134217727} is within the count and past the states');
        self::assertSame(PatternLimit::MachineStates, $read->limit);
    }

    /**
     * Groups are read with a stack of their own, and so are the anchors placed, so text nested far
     * past any limit is read to its end.
     */
    #[RunInSeparateProcess, PreserveGlobalState(false)]
    public function testTextNestedFarPastTheDepthIsReadToItsEnd(): void
    {
        // PHP's default, which reading text this deep stays within.
        ini_set('memory_limit', '128M');
        $deep = 100_000;
        $read = Pattern::read(str_repeat('(', $deep) . '^a$' . str_repeat(')', $deep));
        self::assertInstanceOf(PatternBeyond::class, $read);
        self::assertSame(PatternLimit::NestingDepth, $read->limit);
        self::assertSame(200, $read->from);
        self::assertSame(PatternRefusal::AnAnchorThisCannotPlace,
            self::refusedOf(str_repeat('(', $deep) . 'a|)^b' . str_repeat(')', $deep - 1))->why);
        self::assertSame(PatternRefusal::SomethingUnclosed, self::refusedOf(str_repeat('(', $deep))->why);
    }

    /**
     * A tree as deep as the text is nested is taken apart before it is let go of, whatever the
     * answer: PHP frees nested objects by recursion in C, and a repetition of each group is what
     * makes each level of the tree a part of its own.
     */
    #[RunInSeparateProcess, PreserveGlobalState(false)]
    public function testATreeAsDeepAsTheTextIsLetGoOfWithoutRecursion(): void
    {
        ini_set('memory_limit', '128M');
        $deep = 50_000;
        $nested = str_repeat('(', $deep) . 'a' . str_repeat(')*', $deep);
        $read = Pattern::read($nested);
        self::assertInstanceOf(PatternBeyond::class, $read);
        self::assertSame(PatternLimit::NestingDepth, $read->limit);
        $read = Pattern::read(str_repeat('(?:a|', $deep) . 'b' . str_repeat(')*', $deep));
        self::assertInstanceOf(PatternBeyond::class, $read);
        self::assertSame(PatternRefusal::SomethingUnclosed, self::refusedOf($nested . '(')->why);
        self::assertSame(PatternRefusal::AnAnchorThisCannotPlace,
            self::refusedOf(str_repeat('(', $deep) . 'a|)^b' . str_repeat(')*', $deep - 1))->why);
    }

    /**
     * Every pattern within the limits has a machine, and one at the limit of states is built and
     * walked within PHP's default memory limit: the limit every implementation holds to is not
     * lowered by how PHP holds a machine.
     */
    #[RunInSeparateProcess, PreserveGlobalState(false)]
    public function testAMachineAtTheLimitOfStatesIsBuiltAndWalkedWithinTheDefaultMemoryLimit(): void
    {
        ini_set('memory_limit', '128M');
        foreach ([
            // 1 + 249998 + 1 states, each copy one symbol and one step past it.
            'a{0,249998}' => [str_repeat('a', 1000), str_repeat('a', 249998), str_repeat('a', 249999)],
            '[a-z]{249998}' => [str_repeat('z', 249998), str_repeat('z', 249997)],
            // Every state of the machine at once, a character at a time.
            '(?:a?){49998}' => [str_repeat('a', 20), 'b'],
        ] as $pattern => $subjects) {
            $read = self::read($pattern);
            $answers = array_map(static fn (string $subject): bool => $read->matches($subject), $subjects);
            self::assertSame(
                match ($pattern) {
                    'a{0,249998}' => [true, true, false],
                    '[a-z]{249998}' => [true, false],
                    default => [true, false],
                },
                $answers,
                $pattern,
            );
        }
    }

    /**
     * The limit of states is reached as well by a pattern that writes as many different sets as it
     * has states, which is the other way a pattern can be large: no set is held as an array of its
     * own, the trees and the machine share one copy of the sets, and the table that holds each set
     * once is let go of when reading ends. Each of these is exactly 250,000 states.
     */
    #[RunInSeparateProcess, PreserveGlobalState(false)]
    public function testAPatternOfAsManyDifferentSetsAsStatesIsReadAndMatchedWithinTheDefaultMemoryLimit(): void
    {
        ini_set('memory_limit', '128M');
        $literals = '';
        $negated = '';
        for ($cp = 0x10000; $cp < 0x10000 + 249_999; $cp++) {
            $character = \Raoh\Notation199x\Internal\Utf8::encode($cp);
            $literals .= $character;
            $negated .= '[^' . $character . ']';
        }
        $read = self::read($literals);
        self::assertFalse($read->matches(''));
        self::assertTrue($read->matches($literals));
        self::assertFalse($read->matches(substr($literals, 4)));
        unset($read);
        $read = self::read($negated);
        self::assertFalse($read->matches(''));
        self::assertTrue($read->matches(str_repeat('a', 249_999)));
        self::assertFalse($read->matches($literals));
        unset($read);
        // Anchors at the edges are placed over the whole sequence, which is read and matched the same.
        $read = self::read('^' . substr($negated, 0, -2 * 7) . '$');
        self::assertTrue($read->matches(str_repeat('a', 249_997)));
    }

    /**
     * What a machine keeps of the sets it has worked out stays about the room it is given, however
     * many characters a subject leads from one set by.
     */
    public function testWhatAMachineKeepsStaysWithinItsRoom(): void
    {
        $subject = '';
        for ($cp = 0x80; $cp < 0x30000; $cp++) {
            if ($cp < 0xD800 || $cp > 0xDFFF) {
                $subject .= \Raoh\Notation199x\Internal\Utf8::encode($cp);
            }
        }
        $was = Machine::$knownBytes;
        Machine::$knownBytes = 1 << 16;
        try {
            $read = self::read('[\\x{80}-\\x{10FFFF}]*');
            $before = memory_get_usage();
            self::assertTrue($read->matches($subject));
            self::assertTrue($read->matches($subject));
            self::assertLessThan(4 << 20, memory_get_usage() - $before);
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * A subject holding bytes that are not UTF-8 is no text, and is accepted by nothing.
     */
    public function testASubjectThatIsNotUtf8IsAcceptedByNothing(): void
    {
        foreach (['.*', '[^a]*', '(?:.|\\n)*', '\\W'] as $pattern) {
            $read = self::read($pattern);
            foreach (["\xff", "a\xed\xa0\x80", "\xc3"] as $subject) {
                self::assertFalse($read->matches($subject), $pattern . ' ' . bin2hex($subject));
            }
        }
        self::assertTrue(self::read('.')->matches("\u{FFFD}"), 'U+FFFD written as itself is a character');
    }

    /**
     * Bytes that are not UTF-8 are turned away where the walk meets them, which is where a
     * character is read for a step not known: after a run gone past at once, after characters whose
     * steps are known, and where a character a step is known for is cut short or ends otherwise.
     * Whatever bytes a subject is, (?:.|\n)* accepts it where it is UTF-8 and nowhere else.
     */
    public function testBytesThatAreNotUtf8AreTurnedAwayWhereverTheWalkMeetsThem(): void
    {
        $valid = ['aé😀a', str_repeat('a', 50) . 'é😀', 'é😀' . str_repeat('a', 50)];
        $invalid = [
            str_repeat('a', 50) . "\xff", "a\xc3", "aé\xc3", "é😀\xf0\x9f\x98", "\xc3\x28", "aé\xffé",
            "\xc0\xaf", "\xe0\x80\x80", "\xed\xa0\x80", "\xf4\x90\x80\x80", "\x80", "é\x80", "\xf8\x88\x80\x80\x80",
        ];
        $palette = ['a', "\n", 'é', '😀', "\xc3", "\xa9", "\x80", "\xff", "\xed\xa0", "\xf0\x9f", "\xe0\x80"];
        $was = Machine::$knownBytes;
        try {
            foreach ([0, 2 << 20] as $room) {
                Machine::$knownBytes = $room;
                foreach (['.*', '[^x]*', '(?:é|😀|a)*', '(?:.|\n)*'] as $pattern) {
                    $read = self::read($pattern);
                    for ($round = 0; $round < 2; $round++) {
                        foreach ($valid as $subject) {
                            self::assertTrue($read->matches($subject), "$pattern $subject");
                        }
                        foreach ($invalid as $subject) {
                            self::assertFalse($read->matches($subject), $pattern . ' ' . bin2hex($subject));
                        }
                    }
                }
                $read = self::read('(?:.|\n)*');
                $rng = 3;
                for ($i = 0; $i < 2000; $i++) {
                    $subject = '';
                    for ($k = 0; $k < 6; $k++) {
                        $rng = ($rng * 1664525 + 1013904223) & 0xFFFFFFFF;
                        $subject .= $palette[($rng >> 8) % count($palette)];
                    }
                    self::assertSame(ScalarValues::invalidUtf8At($subject) === null, $read->matches($subject), bin2hex($subject));
                }
            }
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * The whole subject is matched, and an anchor at an edge adds nothing; $ is the end of the
     * string, and not before a line feed at its end.
     */
    public function testTheWholeSubjectIsMatched(): void
    {
        $read = self::read('^[0-9]{3}$');
        self::assertTrue($read->matches('123'));
        self::assertFalse($read->matches("123\n"));
        self::assertFalse($read->matches('1234'));
        self::assertFalse(self::read('abc')->matches('xabcx'));
        self::assertFalse(self::read('a^b')->matches('ab'));
    }

    /**
     * Which sets a walk keeps, and whether it keeps any, changes how fast it is and no answer:
     * every pattern accepts the same subjects with nothing kept, with so little kept that it is
     * forgotten every few characters, and with the room a walk is given.
     */
    public function testWhatAWalkKeepsChangesNoAnswer(): void
    {
        $pieces = ['a', 'b', 'é', '😀', '.', '[ab]', '[^a]', '\\w', '(?:a|b)', '(?:ab|a)', 'a*', 'b+', '(?:a|é)?', '[a-é]{1,3}', '(?:😀|.)*', '^', '$'];
        $letters = ['a', 'b', 'é', '😀', 'c', "\n"];
        $rng = 1;
        $next = static function (int $n) use (&$rng): int {
            $rng = ($rng * 1664525 + 1013904223) & 0xFFFFFFFF;
            return ($rng >> 8) % $n;
        };
        $was = Machine::$knownBytes;
        try {
            for ($i = 0; $i < 500; $i++) {
                $pattern = '';
                for ($k = $next(6); $k >= 0; $k--) {
                    $pattern .= $pieces[$next(count($pieces))];
                }
                $subjects = [];
                for ($k = 0; $k < 8; $k++) {
                    $subject = '';
                    for ($m = $next(12); $m > 0; $m--) {
                        $subject .= $letters[$next(count($letters))];
                    }
                    $subjects[] = $subject;
                }
                $answers = [];
                foreach ([0, 1500, 2 << 20] as $room) {
                    Machine::$knownBytes = $room;
                    $read = Pattern::read($pattern);
                    if (!$read instanceof Pattern) {
                        break;
                    }
                    $these = [];
                    for ($round = 0; $round < 2; $round++) {
                        foreach ($subjects as $subject) {
                            $these[] = $read->matches($subject);
                        }
                    }
                    $answers[] = $these;
                }
                foreach (array_slice($answers, 1) as $these) {
                    self::assertSame($answers[0] ?? null, $these, "$pattern answers otherwise with what is kept");
                }
            }
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * A run of ASCII characters that leads a kept set back to itself is gone past at once, which
     * changes no answer: long subjects, where runs end in a character that leads elsewhere, at the
     * end or partway, answer as a walk that keeps nothing and goes a character at a time.
     */
    public function testARunGonePastAtOnceChangesNoAnswer(): void
    {
        $patterns = ['[a-z ]*', '[a-z ]*x', 'a*b*', '(?:ab)*', '[^x]*x[^x]*', '(?:[a-z]+ )*[a-z]+', '.*é.*', '[ -~]{0,500}'];
        $subjects = [
            str_repeat('lorem ipsum ', 100),
            str_repeat('lorem ipsum ', 100) . 'x',
            str_repeat('a', 300) . str_repeat('b', 300),
            str_repeat('ab', 300) . 'a',
            str_repeat('y', 200) . 'x' . str_repeat('y', 200),
            str_repeat('y', 200) . 'x' . str_repeat('y', 200) . 'x',
            str_repeat('word ', 99) . 'word',
            str_repeat('a', 250) . 'é' . str_repeat('a', 250),
            str_repeat('~', 500),
            str_repeat('~', 501),
            '',
        ];
        $was = Machine::$knownBytes;
        try {
            foreach ($patterns as $pattern) {
                $answers = [];
                foreach ([0, 2 << 20] as $room) {
                    Machine::$knownBytes = $room;
                    $read = self::read($pattern);
                    $these = [];
                    for ($round = 0; $round < 2; $round++) {
                        foreach ($subjects as $subject) {
                            $these[] = $read->matches($subject);
                        }
                    }
                    $answers[] = $these;
                }
                self::assertSame($answers[0], $answers[1], $pattern);
            }
        } finally {
            Machine::$knownBytes = $was;
        }
    }

    /**
     * A match takes time linear in the subject: a subject ten times as long takes about ten times
     * as long, for a pattern a backtracking engine takes exponential time on.
     */
    public function testAMatchIsLinearInTheSubject(): void
    {
        $read = self::read('(?:a|a)*(?:a|a)*b');
        $read->matches('a');
        $short = self::timed(static fn () => $read->matches(str_repeat('a', 2_000)));
        $long = self::timed(static fn () => $read->matches(str_repeat('a', 20_000)));
        self::assertLessThan(40 * max($short, 1e-4), $long);
    }

    private static function timed(callable $f): float
    {
        $start = hrtime(true);
        $f();
        return (hrtime(true) - $start) / 1e9;
    }
}
