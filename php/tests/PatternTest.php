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
        // Each level of the tree is about a kilobyte of PHP objects.
        ini_set('memory_limit', '512M');
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
        ini_set('memory_limit', '512M');
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
