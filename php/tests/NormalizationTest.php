<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\Attributes\PreserveGlobalState;
use PHPUnit\Framework\Attributes\RunInSeparateProcess;
use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Composing;
use Raoh\Notation199x\Internal\FormFacts;
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
     * A starter and a combining run of two million marks, held to a bound of ten, is found past the
     * bound before the run is held: what is made is the room for the bound and the marks that may
     * compose, not for the run, so it is answered within PHP's default memory limit and makes next
     * to nothing.
     */
    #[RunInSeparateProcess, PreserveGlobalState(false)]
    public function testABoundedNormalizationHoldsNoMoreThanItsBound(): void
    {
        ini_set('memory_limit', '128M');
        $text = 'a' . str_repeat("\u{0301}", 2_000_000);
        foreach (NormalizationForm::cases() as $form) {
            // The tables are loaded the first time they are asked, once for the request.
            Normalization::normalize($form, "a\u{0301}");
            $before = memory_get_usage();
            memory_reset_peak_usage();
            $within = Normalization::normalizeWithin($form, $text, 10);
            $made = memory_get_peak_usage() - $before;
            self::assertNull($within, $form->name);
            self::assertLessThan(64 << 10, $made, "{$form->name} made $made bytes");
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

    /**
     * A stable starter of a form is a code point whose combining class is 0 and whose quick check
     * for the form is Yes, as UnicodeData.txt and DerivedNormalizationProps.txt state them: the
     * generated table is held to a reading of the database of this test's own.
     */
    public function testAStableStarterIsAStarterWhoseQuickCheckIsYes(): void
    {
        $marks = [];
        foreach (explode("\n", Repository::read('ucd/18.0.0/UnicodeData.txt')) as $line) {
            $f = explode(';', $line);
            if (count($f) > 3 && $f[3] !== '0') {
                $marks[(int) hexdec($f[0])] = true;
            }
        }
        $notYes = ['NFC' => [], 'NFD' => [], 'NFKC' => [], 'NFKD' => []];
        foreach (explode("\n", Repository::read('ucd/18.0.0/DerivedNormalizationProps.txt')) as $line) {
            $f = array_map('trim', explode(';', explode('#', $line, 2)[0]));
            if (count($f) !== 3 || !str_ends_with($f[1], '_QC') || $f[2] === 'Y') {
                continue;
            }
            $range = explode('..', $f[0]);
            $from = (int) hexdec($range[0]);
            $to = (int) hexdec($range[1] ?? $range[0]);
            for ($cp = $from; $cp <= $to; $cp++) {
                $notYes[substr($f[1], 0, -3)][$cp] = true;
            }
        }
        $wrong = [];
        foreach (['NFC' => 1, 'NFD' => 2, 'NFKC' => 4, 'NFKD' => 8] as $form => $bit) {
            self::assertNotSame([], $notYes[$form], "$form's quick check was read");
            for ($cp = 0; $cp <= 0x10FFFF; $cp++) {
                $stated = !isset($marks[$cp]) && !isset($notYes[$form][$cp]);
                $held = (ord(NormalizationTables::STABLE_PAGES[ord(NormalizationTables::STABLE_BLOCKS[$cp >> 8]) << 8 | $cp & 0xFF]) & $bit) !== 0;
                if ($stated !== $held && count($wrong) < 20) {
                    $wrong[] = sprintf('%s U+%04X', $form, $cp);
                }
            }
        }
        self::assertSame([], $wrong);
    }

    /**
     * The text is kept as it is where it holds stable starters, and the algorithm is run only from
     * the stable starter before a character that is not one up to the next stable starter. That
     * is right only where the answer is what the algorithm gives over the whole text, so the two
     * are held to each other, in every form and within every bound around the answer's length.
     * The texts mix stable starters of several scripts, some past the basic plane, with marks,
     * composites that decompose in one form and not another, compatibility characters, Hangul jamo
     * and the kana voicing marks, so that a text goes in and out of the algorithm many times.
     */
    public function testRunByRunIsTheAlgorithmOverTheWholeTextInEveryForm(): void
    {
        $alphabet = [
            0x61, 0x65, 0x41, 0x20, 0x2E, 0x00E9, 0x00C7,
            0x3042, 0x304B, 0x30AB, 0x65E5, 0x672C,
            0xAC00, 0xAC01, 0xD55C,
            0x20B9F, 0x1F600,
            0x0300, 0x0301, 0x0323, 0x0327, 0x05B0,
            0x3099, 0x309A, 0x309B,
            0x1100, 0x1161, 0x11A8,
            0xFB01, 0x3231, 0xFF76, 0xFF9E, 0x00A0, 0x2126,
            0x0B47, 0x0B3E, 0x1D15E, 0x0344,
        ];
        mt_srand(1999);
        $failed = [];
        for ($n = 0; $n < 3000; $n++) {
            $s = '';
            for ($i = mt_rand(0, 39); $i > 0; $i--) {
                $character = Utf8::encode($alphabet[mt_rand(0, count($alphabet) - 1)]);
                // Runs of stable starters long enough to be kept, between what is not.
                $s .= str_repeat($character, mt_rand(0, 3) === 0 ? mt_rand(1, 6) : 1);
            }
            foreach (NormalizationForm::cases() as $form) {
                $whole = self::whole($form, $s);
                if (Normalization::normalize($form, $s) !== $whole && count($failed) < 20) {
                    $failed[] = $form->name . ' ' . Repository::shown($s);
                }
                $length = ScalarValues::count($whole);
                for ($longest = max(0, $length - 2); $longest <= $length + 1; $longest++) {
                    $within = Normalization::normalizeWithin($form, $s, $longest);
                    if ($within !== ($longest >= $length ? $whole : null) && count($failed) < 20) {
                        $failed[] = $form->name . " within $longest " . Repository::shown($s);
                    }
                }
            }
        }
        self::assertSame([], $failed);
    }

    /**
     * Text that is its own normalization is answered with itself, whether or not the algorithm
     * went over part of it, and a text the algorithm changes in one place keeps the rest as it is.
     */
    public function testTextThatIsItsOwnNormalizationIsAnsweredWithItself(): void
    {
        $japanese = str_repeat('日本語のテキスト、ガギグ。', 10);
        foreach ([$japanese, str_repeat('한국어 텍스트', 10), str_repeat("Renée Ångström à l'école", 10)] as $s) {
            self::assertSame($s, Normalization::normalize(NormalizationForm::NFC, $s));
        }
        self::assertSame($japanese, Normalization::normalize(NormalizationForm::NFKC, $japanese));
        $marked = str_repeat("abc\u{0327}\u{0301}def", 10);
        self::assertSame($marked, Normalization::normalize(NormalizationForm::NFD, $marked));
        self::assertSame($japanese . 'が' . $japanese,
            Normalization::normalize(NormalizationForm::NFC, $japanese . "か\u{3099}" . $japanese));
    }

    private static function decodeHex(string $field): string
    {
        $out = '';
        foreach (preg_split('/\s+/', trim($field)) ?: [] as $token) {
            $out .= Utf8::encode((int) hexdec($token));
        }
        return $out;
    }

    /**
     * $s in $form by the algorithm over the whole text, keeping none of it as it is: one run of
     * Composing from the start to the end.
     */
    private static function whole(NormalizationForm $form, string $s): string
    {
        $composing = new Composing(FormFacts::of($form), -1);
        self::assertSame(strlen($s), $composing->run($s, 0, strlen($s), 0));
        return $composing->answer();
    }
}
