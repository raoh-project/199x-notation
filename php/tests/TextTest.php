<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\CaseConversion;
use Raoh\Notation199x\ScalarValues;
use Raoh\Notation199x\TemporalAnswer;
use Raoh\Notation199x\TemporalKind;
use Raoh\Notation199x\TemporalText;

final class TextTest extends TestCase
{
    public function testInvalidUtf8AtIsWhereTheFirstByteThatIsNoCharacterIs(): void
    {
        foreach ([
            ['', null],
            ['abc', null],
            ["é😀\u{FFFD}", null],
            ["\xff", 0],
            ["ab\xff", 2],
            ["é\xed\xa0\x80", 2], // a surrogate in UTF-8's form
            ["😀\xf0\x9f\x98", 4], // a character cut short
            ["\xc0\x80", 0], // an overlong NUL
            ["\xe0\x80\xaf", 0], // an overlong solidus
            ["\xf4\x90\x80\x80", 0], // past U+10FFFF
        ] as [$text, $at]) {
            self::assertSame($at, ScalarValues::invalidUtf8At($text), bin2hex($text));
        }
    }

    /**
     * invalidUtf8At answers as PCRE's check of UTF-8 does, and where text is not UTF-8 it points
     * at the first place it stops being: on every byte string of up to three bytes from a set
     * that holds every kind of byte a UTF-8 sequence has.
     */
    public function testInvalidUtf8AtAgreesWithAScanOfEveryShortString(): void
    {
        $bytes = [0x00, 0x61, 0x7F, 0x80, 0xBF, 0xC0, 0xC2, 0xDF, 0xE0, 0xED, 0xEF, 0xF0, 0xF4, 0xF5, 0xFF, 0xA0, 0x9F];
        $strings = [''];
        for ($length = 1; $length <= 3; $length++) {
            $longer = [];
            foreach ($strings as $prefix) {
                if (strlen($prefix) === $length - 1) {
                    foreach ($bytes as $b) {
                        $longer[] = $prefix . chr($b);
                    }
                }
            }
            array_push($strings, ...$longer);
        }
        foreach ($strings as $s) {
            $at = ScalarValues::invalidUtf8At($s);
            $valid = preg_match('//u', $s) === 1;
            self::assertSame($valid, $at === null, bin2hex($s));
            if ($at !== null) {
                self::assertSame(1, preg_match('//u', substr($s, 0, $at)), bin2hex($s) . " is UTF-8 before $at");
                // No longer prefix of what is left is a character.
                for ($n = 1; $n <= 4 && $at + $n <= strlen($s); $n++) {
                    self::assertNotSame(1, preg_match('//u', substr($s, $at, $n)), bin2hex($s) . " has a character at $at");
                }
            }
        }
    }

    public function testATextIsCountedAndComparedByScalarValue(): void
    {
        self::assertSame(3, ScalarValues::count('é😀a'));
        self::assertSame(0, ScalarValues::count(''));
        // U+FF61 is above U+1F600's first UTF-16 unit and below the scalar value.
        self::assertSame(-1, ScalarValues::compare('｡', "\u{1F600}"));
        self::assertSame(1, ScalarValues::compare("\u{1F600}", '｡'));
        self::assertSame(-1, ScalarValues::compare('ab', 'abc'));
        self::assertSame(0, ScalarValues::compare('abc', 'abc'));
        // Two numeric strings are compared as text, not as the numbers they write.
        self::assertSame(-1, ScalarValues::compare('10', '9'));
        self::assertSame(-1, ScalarValues::compare('10', '1e1'));
    }

    public function testTheSigmaIsFinalOnlyAtTheEndOfACasedRun(): void
    {
        foreach ([
            'ΟΣ' => 'ος',
            'ΟΣΑ' => 'οσα',
            'Σ' => 'σ',
            'ΟΣ.' => 'ος.',
            "Ο'Σ'" => "ο'ς'",
            "\u{0345}Σ" => "\u{0345}σ",
            "ΟΣ\u{00AD}Α" => "οσ\u{00AD}α",
        ] as $text => $lower) {
            self::assertSame($lower, CaseConversion::lowercase($text), $text);
        }
        self::assertNull(CaseConversion::uppercaseWithin('straße', 6));
        self::assertSame('STRASSE', CaseConversion::uppercaseWithin('straße', 7));
        self::assertNull(CaseConversion::lowercaseWithin('', -1));
        self::assertSame('', CaseConversion::lowercaseWithin('', 0));
    }

    public function testTextACaseConversionLeavesAsItIsIsAnsweredWithItself(): void
    {
        foreach (['', 'hello, world', '日本語のテキスト。', 'ος'] as $text) {
            self::assertSame($text, CaseConversion::lowercase($text));
        }
        self::assertSame('HELLO, 日本', CaseConversion::uppercase('hello, 日本'));
        // A run that maps to itself still counts against a bound.
        self::assertNull(CaseConversion::lowercaseWithin('abc', 2));
        self::assertSame('abc', CaseConversion::lowercaseWithin('abc', 3));
    }

    public function testALeapSecondIsRefusedForWhatItIs(): void
    {
        foreach ([
            '2016-12-31T23:59:60Z' => TemporalAnswer::LeapSecond,
            '2016-12-31T23:59:59Z' => TemporalAnswer::Admitted,
            '2016-12-31T24:00:60Z' => TemporalAnswer::Malformed,
            '+1000000000-12-31T23:59:60Z' => TemporalAnswer::LeapSecond,
            '+1000000000-12-31T23:59:60-00:00:01' => TemporalAnswer::Malformed,
        ] as $text => $answer) {
            self::assertSame($answer, TemporalText::check(TemporalKind::Instant, $text), $text);
        }
        self::assertSame(TemporalAnswer::Malformed, TemporalText::check(TemporalKind::DateTime, '2016-12-31T23:59:60'));
    }

    public function testATemporalIsReadInBytesThatAreASCII(): void
    {
        self::assertSame(TemporalAnswer::Admitted, TemporalText::check(TemporalKind::Date, '2026-10-02'));
        self::assertSame(TemporalAnswer::Malformed, TemporalText::check(TemporalKind::Date, '２０２６-10-02'));
        self::assertSame(TemporalAnswer::Malformed, TemporalText::check(TemporalKind::Date, "2026-10-02\xff"));
        self::assertSame(TemporalAnswer::Malformed, TemporalText::check(TemporalKind::Date, '-0000-01-01'));
        self::assertSame(TemporalAnswer::Admitted, TemporalText::check(TemporalKind::Date, '-0001-01-01'));
        self::assertSame(TemporalAnswer::Admitted, TemporalText::check(TemporalKind::Instant, '-1000000000-01-01T00:00:00Z'));
        self::assertSame(TemporalAnswer::Malformed, TemporalText::check(TemporalKind::Instant, '-1000000000-01-01T00:00:00+00:00:01'));
    }
}
