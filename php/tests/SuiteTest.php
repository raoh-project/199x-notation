<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\CaseConversion;
use Raoh\Notation199x\Normalization;
use Raoh\Notation199x\NormalizationForm;
use Raoh\Notation199x\Pattern;
use Raoh\Notation199x\PatternBeyond;
use Raoh\Notation199x\PatternLimit;
use Raoh\Notation199x\PatternRefused;
use Raoh\Notation199x\ScalarValues;
use Raoh\Notation199x\TemporalAnswer;
use Raoh\Notation199x\TemporalKind;
use Raoh\Notation199x\TemporalText;
use Raoh\Notation199x\Tests\Support\Line;
use Raoh\Notation199x\Tests\Support\Repository;
use Raoh\Notation199x\WhiteSpace;

/**
 * The vectors in the repository's suite directory, every line of every file.
 */
final class SuiteTest extends TestCase
{
    public function testCaseConversionAnswersEveryLineOfTheSuite(): void
    {
        Repository::eachLine('case.txt', 5, static function (Line $l): ?string {
            $text = $l->text(0);
            $lower = $l->oneOf(1, 'LOWER', 'UPPER') === 'LOWER';
            $bound = $l->numberOrNothing(2);
            $past = $l->oneOf(3, 'MAPPED', 'PAST') === 'PAST';
            $mapped = null;
            if ($past) {
                $l->empty(4);
            } else {
                $mapped = $l->text(4);
            }
            if ($bound === null && $past) {
                return 'a conversion without a bound is never past one';
            }
            $answer = match (true) {
                $bound === null && $lower => CaseConversion::lowercase($text),
                $bound === null => CaseConversion::uppercase($text),
                $lower => CaseConversion::lowercaseWithin($text, $bound),
                default => CaseConversion::uppercaseWithin($text, $bound),
            };
            if ($answer !== $mapped) {
                return sprintf('%s is %s, not %s', Repository::shown($text), self::shownOrNull($answer), self::shownOrNull($mapped));
            }
            return null;
        });
    }

    public function testNormalizationWithinABoundAnswersEveryLineOfTheSuite(): void
    {
        Repository::eachLine('normalization-bound.txt', 5, static function (Line $l): ?string {
            $text = $l->text(0);
            $form = constant(NormalizationForm::class . '::' . $l->oneOf(1, 'NFC', 'NFD', 'NFKC', 'NFKD'));
            self::assertInstanceOf(NormalizationForm::class, $form);
            $bound = $l->number(2);
            $past = $l->oneOf(3, 'NORMALIZED', 'PAST') === 'PAST';
            $normalized = null;
            if ($past) {
                $l->empty(4);
            } else {
                $normalized = $l->text(4);
            }
            $answer = Normalization::normalizeWithin($form, $text, $bound);
            if ($answer !== $normalized) {
                return sprintf('%s in %s within %d is %s', Repository::shown($text), $form->name, $bound, self::shownOrNull($answer));
            }
            return null;
        });
    }

    public function testWhiteSpaceIsTheSetTheSuiteLists(): void
    {
        $listed = [];
        Repository::eachLine('white-space.txt', 1, static function (Line $l) use (&$listed): ?string {
            $listed[$l->scalar(0)] = true;
            return null;
        });
        $wrong = [];
        for ($cp = 0; $cp <= 0x10FFFF; $cp++) {
            if ($cp >= 0xD800 && $cp <= 0xDFFF) {
                continue;
            }
            if (WhiteSpace::contains($cp) !== isset($listed[$cp])) {
                $wrong[] = sprintf('U+%04X', $cp);
            }
        }
        self::assertSame([], $wrong, 'white space where the suite does not list it, or the other way');
    }

    public function testScalarLengthAnswersEveryLineOfTheSuite(): void
    {
        Repository::eachLine('scalar-length.txt', 2, static function (Line $l): ?string {
            $text = $l->text(0);
            $length = $l->number(1);
            $answer = ScalarValues::count($text);
            return $answer === $length ? null : sprintf('%s is %d long, not %d', Repository::shown($text), $answer, $length);
        });
    }

    public function testScalarOrderAnswersEveryLineOfTheSuite(): void
    {
        $orders = ['LESS' => -1, 'EQUAL' => 0, 'GREATER' => 1];
        Repository::eachLine('scalar-order.txt', 3, static function (Line $l) use ($orders): ?string {
            $a = $l->text(0);
            $b = $l->text(1);
            $order = $orders[$l->oneOf(2, 'LESS', 'EQUAL', 'GREATER')];
            if (ScalarValues::compare($a, $b) !== $order || ScalarValues::compare($b, $a) !== -$order) {
                return sprintf('%s against %s is %d, not %d', Repository::shown($a), Repository::shown($b), ScalarValues::compare($a, $b), $order);
            }
            return null;
        });
    }

    public function testTemporalTextAnswersEveryLineOfTheSuite(): void
    {
        $kinds = [
            'DATE' => TemporalKind::Date,
            'TIME' => TemporalKind::Time,
            'DATETIME' => TemporalKind::DateTime,
            'OFFSET_DATETIME' => TemporalKind::OffsetDateTime,
            'INSTANT' => TemporalKind::Instant,
        ];
        Repository::eachLine('temporal.txt', 3, static function (Line $l) use ($kinds): ?string {
            $kind = $kinds[$l->oneOf(0, ...array_keys($kinds))];
            $text = $l->text(1);
            $admitted = $l->oneOf(2, 'ADMITTED', 'REFUSED') === 'ADMITTED';
            $answer = TemporalText::check($kind, $text);
            if (($answer === TemporalAnswer::Admitted) !== $admitted) {
                return sprintf('%s as %s is %s', Repository::shown($text), $kind->name, $answer->name);
            }
            return null;
        });
    }

    public function testPatternsAreReadAsEveryLineOfTheSuiteSays(): void
    {
        $limits = [
            'REPETITION_COUNT' => PatternLimit::RepetitionCount,
            'NESTING_DEPTH' => PatternLimit::NestingDepth,
            'MACHINE_STATES' => PatternLimit::MachineStates,
        ];
        Repository::eachLine('pattern-read.txt', 3, static function (Line $l) use ($limits): ?string {
            $pattern = $l->text(0);
            $outcome = $l->oneOf(1, 'READ', 'REFUSED', 'BEYOND');
            $limit = null;
            if ($outcome === 'BEYOND') {
                $limit = $l->oneOfOrNothing(2, ...array_keys($limits));
            } else {
                $l->empty(2);
            }
            $read = Pattern::read($pattern);
            $asSaid = match (true) {
                $read instanceof Pattern => $outcome === 'READ',
                $read instanceof PatternRefused => $outcome === 'REFUSED',
                default => $outcome === 'BEYOND' && ($limit === null || $read->limit === $limits[$limit]),
            };
            return $asSaid ? null : sprintf('%s is %s, not %s %s', Repository::shown($pattern), self::described($read), $outcome, $limit ?? '');
        });
    }

    public function testPatternsAcceptWhatEveryLineOfTheSuiteSays(): void
    {
        Repository::eachLine('pattern-match.txt', 3, static function (Line $l): ?string {
            $pattern = $l->text(0);
            $subject = $l->text(1);
            $accepted = $l->yesOrNo(2);
            $read = Pattern::read($pattern);
            if (!$read instanceof Pattern) {
                return sprintf('%s is not read: %s', Repository::shown($pattern), self::described($read));
            }
            if ($read->matches($subject) !== $accepted) {
                return sprintf('%s accepts %s: %s', Repository::shown($pattern), Repository::shown($subject), $accepted ? 'false' : 'true');
            }
            return null;
        });
    }

    public function testPatternStatesAreCountedAsEveryLineOfTheSuiteSays(): void
    {
        // The states the specifications state the limit in, which the file's rule is written
        // against: not the one this implementation holds, which is what is tested.
        $mostStates = 250_000;
        Repository::eachLine('pattern-states.txt', 2, static function (Line $l) use ($mostStates): ?string {
            $pattern = $l->text(0);
            $states = $l->number(1);
            $at = Pattern::read('(?:' . $pattern . ')|a{0,' . ($mostStates - 5 - $states) . '}');
            $past = Pattern::read('(?:' . $pattern . ')|a{0,' . ($mostStates - 4 - $states) . '}');
            if (!$at instanceof Pattern) {
                return sprintf('%s at the limit is %s', Repository::shown($pattern), self::described($at));
            }
            if (!$past instanceof PatternBeyond || $past->limit !== PatternLimit::MachineStates) {
                return sprintf('%s past the limit is %s', Repository::shown($pattern), self::described($past));
            }
            return null;
        });
    }

    private static function shownOrNull(?string $text): string
    {
        return $text === null ? 'null' : Repository::shown($text);
    }

    private static function described(Pattern|PatternRefused|PatternBeyond $read): string
    {
        return match (true) {
            $read instanceof Pattern => 'a pattern',
            $read instanceof PatternRefused => sprintf('refused as %s at %d', $read->why->name, $read->from),
            default => sprintf('beyond %s at %d', $read->limit->name, $read->from),
        };
    }
}
