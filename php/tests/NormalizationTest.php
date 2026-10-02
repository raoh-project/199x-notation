<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\NormalizationTables;
use Raoh\Notation199x\Internal\Utf8;
use Raoh\Notation199x\Normalization;
use Raoh\Notation199x\NormalizationForm;
use Raoh\Notation199x\ScalarValues;
use Raoh\Notation199x\Tests\Support\Repository;

final class NormalizationTest extends TestCase
{
    /**
     * The four forms against Unicode's own NormalizationTest.txt, in full. Each data line is five
     * columns, source, NFC, NFD, NFKC and NFKD, and the file states what conformance is:
     * c2 == toNFC(c1) == toNFC(c2) == toNFC(c3) and c4 == toNFC(c4) == toNFC(c5);
     * c3 == toNFD(c1) == toNFD(c2) == toNFD(c3) and c5 == toNFD(c4) == toNFD(c5);
     * c4 == toNFKC and c5 == toNFKD of all five. A code point no line of Part 1 names is its own
     * normalization in every form.
     */
    public function testNormalizationAnswersEveryLineOfTheConformanceTest(): void
    {
        $lines = explode("\n", Repository::read('ucd/18.0.0/NormalizationTest.txt'));
        self::assertSame('# NormalizationTest-18.0.0.txt', $lines[0]);
        $named = [];
        $partOne = false;
        $checked = 0;
        $failed = [];
        foreach ($lines as $n => $text) {
            $text = trim(explode('#', $text, 2)[0]);
            if (str_starts_with($text, '@')) {
                $partOne = $text === '@Part1';
                continue;
            }
            if ($text === '') {
                continue;
            }
            $columns = explode(';', $text);
            $c = [];
            for ($i = 1; $i <= 5; $i++) {
                $c[$i] = self::decodeHex($columns[$i - 1]);
            }
            if ($partOne) {
                $named[Utf8::decode($c[1])] = true;
            }
            $checked++;
            for ($i = 1; $i <= 5; $i++) {
                [$nfc, $nfd] = $i <= 3 ? [$c[2], $c[3]] : [$c[4], $c[5]];
                foreach ([[NormalizationForm::NFC, $nfc], [NormalizationForm::NFD, $nfd],
                    [NormalizationForm::NFKC, $c[4]], [NormalizationForm::NFKD, $c[5]]] as [$form, $want]) {
                    $got = Normalization::normalize($form, $c[$i]);
                    if ($got !== $want && count($failed) < 20) {
                        $failed[] = sprintf('line %d: %s(c%d %s) is %s, not %s', $n + 1, $form->name, $i,
                            Repository::shown($c[$i]), Repository::shown($got), Repository::shown($want));
                    }
                }
            }
        }
        self::assertGreaterThanOrEqual(10_000, $checked, "the file's data lines were read");
        for ($cp = 0; $cp <= 0x10FFFF; $cp++) {
            if (isset($named[$cp]) || $cp >= 0xD800 && $cp <= 0xDFFF) {
                continue;
            }
            $alone = Utf8::encode($cp);
            foreach (NormalizationForm::cases() as $form) {
                if (Normalization::normalize($form, $alone) !== $alone && count($failed) < 20) {
                    $failed[] = sprintf('%s changes U+%04X, which Part 1 does not name', $form->name, $cp);
                }
            }
        }
        self::assertSame([], $failed);
    }

    /**
     * A text normalized one combining run at a time is the text normalized whole: a bound that is
     * the answer's length takes it, and one less does not.
     */
    public function testABoundIsHeldOnTheAnswer(): void
    {
        foreach (['', 'a', 'Å', 'Ǻ', 'ﬃ', '㌀', '가', "\u{1D15E}", 'ȩ́́x'] as $text) {
            foreach (NormalizationForm::cases() as $form) {
                $whole = Normalization::normalize($form, $text);
                $length = ScalarValues::count($whole);
                self::assertSame($whole, Normalization::normalizeWithin($form, $text, $length));
                if ($length > 0) {
                    self::assertNull(Normalization::normalizeWithin($form, $text, $length - 1));
                }
                self::assertNull(Normalization::normalizeWithin($form, $text, -1));
            }
        }
    }

    /**
     * Normalization goes past a run of ASCII at once, as text below every form's trivial limit.
     */
    public function testASCIIIsBelowEveryTrivialLimit(): void
    {
        foreach ([
            NormalizationTables::NFC_TRIVIAL_LIMIT,
            NormalizationTables::NFD_TRIVIAL_LIMIT,
            NormalizationTables::NFKC_TRIVIAL_LIMIT,
            NormalizationTables::NFKD_TRIVIAL_LIMIT,
        ] as $limit) {
            self::assertGreaterThan(0x7F, $limit);
        }
    }

    public function testTheFormsNormalizeAsTheirNamesSay(): void
    {
        self::assertSame("\u{00C5}", Normalization::normalize(NormalizationForm::NFC, "A\u{030A}"));
        self::assertSame('ffi', Normalization::normalize(NormalizationForm::NFKD, 'ﬃ'));
        self::assertSame("\u{1100}\u{1161}\u{11A8}", Normalization::normalize(NormalizationForm::NFD, '각'));
        self::assertSame('각', Normalization::normalize(NormalizationForm::NFC, "\u{1100}\u{1161}\u{11A8}"));
    }

    /**
     * More marks after one starter than are put in order by insertion are put in the same order,
     * stably, by combining class.
     */
    public function testAManyMarkRunIsOrderedAsAFewMarkRunIs(): void
    {
        $marks = str_repeat("\u{0301}\u{0316}", 40);
        $expected = 'a' . str_repeat("\u{0316}", 40) . str_repeat("\u{0301}", 40);
        self::assertSame($expected, Normalization::normalize(NormalizationForm::NFD, 'a' . $marks));
        self::assertSame("\u{00E1}" . str_repeat("\u{0316}", 40) . str_repeat("\u{0301}", 39),
            Normalization::normalize(NormalizationForm::NFC, 'a' . $marks));
    }

    private static function decodeHex(string $field): string
    {
        $out = '';
        foreach (preg_split('/\s+/', trim($field)) ?: [] as $token) {
            $out .= Utf8::encode((int) hexdec($token));
        }
        return $out;
    }
}
