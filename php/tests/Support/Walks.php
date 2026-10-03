<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests\Support;

use Raoh\Notation199x\CaseConversion;
use Raoh\Notation199x\Internal\Composing;
use Raoh\Notation199x\Internal\NormalizationTables;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Internal\Utf8;
use Raoh\Notation199x\Normalization;
use Raoh\Notation199x\NormalizationForm;
use Raoh\Notation199x\Pattern;

/**
 * What bench/walks.php measures, in one place, each case with what shows it takes the path it is
 * named for. WalksTest runs each case once and asks that, so a case that stops taking its path
 * fails the tests rather than measuring something else under its name.
 *
 * A match has three paths: keeping a set it has not kept, stepping by steps already kept, and
 * walking a state at a time without kept sets. Each is measured with sets as small as a few states
 * and as large as thousands. The rest are the measurements #30 and c8f5f74 gave.
 */
final class Walks
{
    /**
     * Each case by name: a function that sets it up and answers the run to time and a function
     * that answers why the run did not take its path, or null where it did. Setting a case up may
     * set Machine's room and how long it waits to try keeping sets again, for the case's runs;
     * whoever runs one puts them back.
     *
     * @return array<string, \Closure(): array{\Closure(): mixed, \Closure(): ?string}>
     */
    public static function cases(): array
    {
        $ascii = str_repeat('hello world ', 86);
        $japanese = 'これは日本語のテキストです。カタカナとｶﾀｶﾅ、漢字。';
        $marks = 'a' . str_repeat("\u{0301}\u{0316}\u{0323}\u{0300}", 250);
        return [
            'match: keep new sets of a few states' => static fn (): array => self::keeping('(?:a|b)*a(?:a|b){12}', self::randomAb(2000)),
            'match: keep new sets of thousands of states' => static fn (): array => self::keeping('(?:[ab]?){3000}', self::randomAb(40)),
            'match: kept steps, non-ASCII' => static fn (): array => self::known(self::machine('[^a]*'), str_repeat('日本語のテキスト、', 40)),
            'match: kept steps, email-like' => static fn (): array => self::known(self::machine('[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}'), 'someone.else@example.co.jp'),
            'match: an ASCII run gone past at once, 1 KB' => static fn (): array => self::run('[a-z ]*', $ascii),
            'match: an ASCII run gone past at once, 100 KB' => static fn (): array => self::run('[a-z ]*', str_repeat('hello world ', 8600)),
            'match: without kept sets, sets of a few states' => static fn (): array => self::alone('(?:a|b)*a(?:a|b){12}', self::randomAb(2000)),
            'match: without kept sets, sets of thousands of states' => static fn (): array => self::alone('(?:[ab]?){20000}', self::randomAb(20)),
            'match: (?:a?){49998}, 10 characters' => static fn (): array => self::alone('(?:a?){49998}', str_repeat('a', 10)),
            'NFC: 1 KB of ASCII, below the trivial limit' => static fn (): array => [
                static fn (): string => Normalization::normalize(NormalizationForm::NFC, $ascii),
                static fn (): ?string => Normalization::normalize(NormalizationForm::NFC, $ascii) === $ascii ? null : 'it changed',
            ],
            'NFC: 31 Japanese characters' => static fn (): array => self::normalizing(NormalizationForm::NFC, $japanese),
            'NFKC: 31 Japanese characters' => static fn (): array => self::normalizing(NormalizationForm::NFKC, $japanese),
            'NFC: a run of 1,000 marks, counted into place' => static fn (): array => self::counting($marks),
            'lowercase: 1 KB of ASCII it leaves as it is' => static fn (): array => [
                static fn (): string => CaseConversion::lowercase($ascii),
                static fn (): ?string => CaseConversion::lowercase($ascii) === $ascii ? null : 'it changed',
            ],
            'uppercase: 1 KB of ASCII' => static fn (): array => [
                static fn (): string => CaseConversion::uppercase($ascii),
                static fn (): ?string => CaseConversion::uppercase($ascii) === strtoupper($ascii) ? null : 'it is not the ASCII uppercase',
            ],
        ];
    }

    /**
     * The match of $subject with room for every set it comes to, the sets forgotten before each
     * run, so every run keeps each set anew. It shows it took the path where it never gave up and
     * kept a new set for at least half the characters.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function keeping(string $pattern, string $subject): array
    {
        Machine::$knownBytes = 1 << 30;
        $m = self::machine($pattern);
        $forget = (new \ReflectionMethod(Machine::class, 'forget'))->getClosure($m);
        return [
            static function () use ($m, $forget, $subject): void {
                $forget();
                $m->matches($subject);
            },
            static function () use ($m, $forget, $subject): ?string {
                $forget();
                $m->matches($subject);
                $made = self::get($m, 'made');
                if (self::get($m, 'off') !== false || !is_int($made) || $made * 2 < strlen($subject)) {
                    return 'it kept ' . var_export($made, true) . ' sets for ' . strlen($subject) . ' characters';
                }
                return null;
            },
        ];
    }

    /**
     * The match of $subject once its sets and steps are kept. It shows it took the path where a
     * run kept no set.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function known(Machine $m, string $subject): array
    {
        $m->matches($subject);
        return [
            static fn (): bool => $m->matches($subject),
            static function () use ($m, $subject): ?string {
                $before = self::get($m, 'made');
                $m->matches($subject);
                return self::get($m, 'made') === $before && self::get($m, 'off') === false ? null : 'it kept a set, or gave up';
            },
        ];
    }

    /**
     * known(), where after its first character the walk is in a set that leads back to itself by
     * every character of $subject, so the rest is one run gone past at once.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function run(string $pattern, string $subject): array
    {
        $m = self::machine($pattern);
        [$time, $known] = self::known($m, $subject);
        return [
            $time,
            static function () use ($known, $m, $subject): ?string {
                $loops = self::get($m, 'keptLoop');
                $rest = substr($subject, 1);
                foreach (is_array($loops) ? $loops : [] as $loop) {
                    if (is_string($loop) && strspn($rest, $loop) === strlen($rest)) {
                        return $known();
                    }
                }
                return 'no set it keeps leads back to itself by every character';
            },
        ];
    }

    /**
     * The match of $subject with no room for a set and no try to keep them again, so every
     * character is walked a state at a time. It shows it took the path where it gave up.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function alone(string $pattern, string $subject): array
    {
        Machine::$knownBytes = 0;
        Machine::$retryWork = PHP_INT_MAX;
        $m = self::machine($pattern);
        $m->matches($subject);
        return [
            static fn (): bool => $m->matches($subject),
            static fn (): ?string => self::get($m, 'off') === true && self::get($m, 'made') === 0 ? null : 'it kept sets',
        ];
    }

    /**
     * The normalization of $s, which shows it reads past the trivial limit, where whether a
     * character is a stable starter is asked of the tables, by holding a code point at or above the
     * form's limit.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function normalizing(NormalizationForm $form, string $s): array
    {
        $limit = match ($form) {
            NormalizationForm::NFC => NormalizationTables::NFC_TRIVIAL_LIMIT,
            NormalizationForm::NFD => NormalizationTables::NFD_TRIVIAL_LIMIT,
            NormalizationForm::NFKC => NormalizationTables::NFKC_TRIVIAL_LIMIT,
            NormalizationForm::NFKD => NormalizationTables::NFKD_TRIVIAL_LIMIT,
        };
        return [
            static fn (): string => Normalization::normalize($form, $s),
            static function () use ($s, $limit): ?string {
                for ($at = 0; $at < strlen($s); $at += Utf8::width(ord($s[$at]))) {
                    if (Utf8::decodeAt($s, $at) >= $limit) {
                        return null;
                    }
                }
                return 'every code point is below the trivial limit';
            },
        ];
    }

    /**
     * The NFC of $s, which shows its marks are counted into place by holding a run of more marks
     * after one starter than are put in order by insertion.
     *
     * @return array{\Closure(): mixed, \Closure(): ?string}
     */
    private static function counting(string $s): array
    {
        [$time, $past] = self::normalizing(NormalizationForm::NFC, $s);
        $few = (new \ReflectionClassConstant(Composing::class, 'FEW_MARKS'))->getValue();
        return [
            $time,
            static function () use ($past, $s, $few): ?string {
                $run = 0;
                $longest = 0;
                foreach (Utf8::split($s) as $character) {
                    $run = isset(NormalizationTables::COMBINING_CLASSES[$character]) ? $run + 1 : 0;
                    $longest = max($longest, $run);
                }
                return $longest > $few ? $past() : "its longest run is $longest marks";
            },
        ];
    }

    private static function machine(string $pattern): Machine
    {
        $read = Pattern::read($pattern);
        if (!$read instanceof Pattern) {
            throw new \LogicException("$pattern is no pattern");
        }
        $read->matches('');
        $m = (new \ReflectionProperty(Pattern::class, 'machine'))->getValue($read);
        if (!$m instanceof Machine) {
            throw new \LogicException("$pattern has no machine");
        }
        return $m;
    }

    private static function get(Machine $m, string $name): mixed
    {
        return (new \ReflectionProperty(Machine::class, $name))->getValue($m);
    }

    private static function randomAb(int $n): string
    {
        $rng = 7;
        $out = '';
        for ($i = 0; $i < $n; $i++) {
            $rng = ($rng * 1664525 + 1013904223) & 0xFFFFFFFF;
            $out .= 'ab'[$rng >> 31];
        }
        return $out;
    }
}
