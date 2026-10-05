<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

/**
 * One pass of canonical ordering and, where the form composes, composition over characters
 * already decomposed: the starter of the run it is in, the marks held after it, and what is
 * settled. A character is held as its UTF-8 bytes throughout, which is what the tables are keyed
 * by; only Hangul, which is arithmetic, is taken to its code point.
 *
 * With a bound, what is written and the least of the answer what is held can come to are no more
 * than the bound, which take() holds to before it holds another mark: the starter, and the marks
 * but those it may compose with, which are no more than NormalizationTables::MOST_MARKS_COMPOSED.
 * So a run holds no more than the bound and that many marks, whatever the length of the text.
 *
 * @internal
 */
final class Composing
{
    // Hangul's algorithmic decomposition and composition, as UAX #15 states them.
    private const S_BASE = 0xAC00;
    private const L_BASE = 0x1100;
    private const V_BASE = 0x1161;
    private const T_BASE = 0x11A7;
    private const L_COUNT = 19;
    private const V_COUNT = 21;
    private const T_COUNT = 28;
    private const N_COUNT = self::V_COUNT * self::T_COUNT;
    private const S_COUNT = self::L_COUNT * self::N_COUNT;

    /** How many marks a run may hold before they are put in order by counting rather than by insertion, which is quadratic in the run. */
    private const FEW_MARKS = 32;

    /**
     * Each character's full decomposition, canonical and compatibility, as it is worked out.
     * Bounded by the characters the tables name.
     *
     * @var array{0: array<string, list<string>>, 1: array<string, list<string>>}
     */
    private static array $decompositions = [[], []];

    /** What run() wrote, the run settled. */
    private string $out = '';
    /** How many scalar values of the answer come before what is held: those before the run, and those it has written. */
    private int $written = 0;
    private ?string $starter = null;
    /**
     * @var array<int, string> the marks held after the starter, from 0 up to their count and in
     *      that order: order writes them into place by number, as Machine's rows are written
     */
    private array $marks = [];

    public function __construct(
        private readonly FormFacts $form,
        private readonly int $longest,
    ) {
    }

    /**
     * Runs the algorithm over $s from byte $start, $before scalar values of the answer coming
     * before it, up to the first stable starter after byte $at, or to the end of the text where
     * none comes, and holds the run settled as answer(). The characters from $start to $at are
     * taken, and so is the one there, whatever they are; with $at the end of the text, all of it
     * from $start is. Answers where it stopped, or -1 where the answer has passed the bound.
     *
     * A step of its loop is one character of the text, decomposed and taken.
     */
    public function run(string $s, int $start, int $at, int $before): int
    {
        $this->out = '';
        $this->written = $before;
        $this->starter = null;
        $this->marks = [];
        $length = strlen($s);
        $j = $start;
        while ($j < $length) {
            $width = Utf8::width(ord($s[$j]));
            $character = substr($s, $j, $width);
            if ($j > $at && $this->stable($character)) {
                break;
            }
            $j += $width;
            $parts = self::decompose($character, $this->form->compatibility);
            if ($parts === null) {
                if (!$this->take($character)) {
                    return -1;
                }
                continue;
            }
            foreach ($parts as $part) {
                if (!$this->take($part)) {
                    return -1;
                }
            }
        }
        return $this->write($this->settle()) ? $j : -1;
    }

    /**
     * What the last run() wrote.
     */
    public function answer(): string
    {
        return $this->out;
    }

    /**
     * How many scalar values of the answer come before where the last run() stopped.
     */
    public function written(): int
    {
        return $this->written;
    }

    /**
     * Whether $character is a stable starter of the form.
     */
    private function stable(string $character): bool
    {
        $cp = Utf8::decode($character);
        return $cp < $this->form->limit || $cp > 0x10FFFF
            || (ord(NormalizationTables::STABLE_PAGES[ord(NormalizationTables::STABLE_BLOCKS[$cp >> 8]) << 8 | $cp & 0xFF]) & $this->form->bit) !== 0;
    }

    /**
     * $character's full decomposition, or null where it is its own: Hangul's arithmetic split, or
     * the tables followed as far as they go, since a decomposition may map to characters that
     * decompose themselves. With $compatibility the compatibility mappings are followed as well.
     *
     * @return list<string>|null
     */
    public static function decompose(string $character, bool $compatibility): ?array
    {
        $memo = self::$decompositions[$compatibility ? 1 : 0][$character] ?? null;
        if ($memo !== null) {
            return $memo;
        }
        $lead = ord($character[0]);
        if ($lead >= 0xEA && $lead <= 0xED) {
            $cp = Utf8::decode($character);
            if (self::isHangulSyllable($cp)) {
                $index = $cp - self::S_BASE;
                $parts = [
                    Utf8::encode(self::L_BASE + intdiv($index, self::N_COUNT)),
                    Utf8::encode(self::V_BASE + intdiv($index % self::N_COUNT, self::T_COUNT)),
                ];
                $t = self::T_BASE + $index % self::T_COUNT;
                if ($t !== self::T_BASE) {
                    $parts[] = Utf8::encode($t);
                }
                return $parts;
            }
        }
        $mapped = NormalizationTables::CANONICAL[$character]
            ?? ($compatibility ? NormalizationTables::COMPATIBILITY[$character] ?? null : null);
        if ($mapped === null) {
            return null;
        }
        $parts = [];
        foreach (Utf8::split($mapped) as $part) {
            $further = self::decompose($part, $compatibility);
            if ($further === null) {
                $parts[] = $part;
            } else {
                array_push($parts, ...$further);
            }
        }
        self::$decompositions[$compatibility ? 1 : 0][$character] = $parts;
        return $parts;
    }

    /**
     * Takes the next decomposed character, and is false where what is written has passed the
     * bound.
     */
    private function take(string $character): bool
    {
        if (isset(NormalizationTables::COMBINING_CLASSES[$character])) {
            // The starter and the marks are no more than one more than the marks, so only where
            // that is past what is left is it asked how few they may come to.
            if ($this->longest >= 0 && count($this->marks) + 2 > $this->longest - $this->written
                && $this->leastHeld(count($this->marks) + 1) > $this->longest - $this->written) {
                return false;
            }
            $this->marks[] = $character;
            return true;
        }
        $kept = $this->settle();
        if ($this->form->composes && $this->starter !== null && $kept === 0) {
            $composed = self::compose($this->starter, $character);
            if ($composed !== null) {
                $this->starter = $composed;
                return true;
            }
        }
        if (!$this->write($kept)) {
            return false;
        }
        $this->starter = $character;
        return true;
    }

    /**
     * The least number of scalar values of the answer the starter held and $marks marks after it
     * come to, whatever follows: every mark, and the starter, but those of the marks it may compose
     * with in a composing form.
     */
    private function leastHeld(int $marks): int
    {
        if ($this->starter === null) {
            return $marks;
        }
        return 1 + ($this->form->composes ? max($marks - NormalizationTables::MOST_MARKS_COMPOSED, 0) : $marks);
    }

    /**
     * Puts the held marks in canonical order and, where the form composes, composes into the
     * starter each one nothing blocks, and answers how many marks are left after the starter.
     */
    private function settle(): int
    {
        if (count($this->marks) > 1) {
            $this->order();
        }
        if (!$this->form->composes || $this->starter === null) {
            return count($this->marks);
        }
        $kept = [];
        $lastClass = -1;
        foreach ($this->marks as $mark) {
            $class = NormalizationTables::COMBINING_CLASSES[$mark];
            if ($lastClass < $class) {
                $composed = self::compose($this->starter, $mark);
                if ($composed !== null) {
                    $this->starter = $composed;
                    continue;
                }
            }
            $kept[] = $mark;
            $lastClass = $class;
        }
        $this->marks = $kept;
        return count($kept);
    }

    /**
     * Puts the held marks in canonical order: stable, by combining class. A run of more marks than
     * FEW_MARKS, which may be as long as the text, is counted into place as Go's is: how many marks
     * there are of each class, where each class begins, and each mark written where its class has
     * come to. The two loops over the run are this method's own and no sort of PHP's; the one over
     * the 256 classes is as long whatever the text. What is held besides the run is the ordered
     * run, filled once with array_fill, and the 257 counts.
     */
    private function order(): void
    {
        $marks = $this->marks;
        if (count($marks) > self::FEW_MARKS) {
            $starts = array_fill(0, 257, 0);
            foreach ($marks as $mark) {
                $starts[NormalizationTables::COMBINING_CLASSES[$mark] + 1]++;
            }
            for ($class = 1; $class < 257; $class++) {
                $starts[$class] += $starts[$class - 1];
            }
            // Every place from 0 to the count is written over once, in the order array_fill made
            // them, so the marks are gone over in the order they were put.
            $ordered = array_fill(0, count($marks), '');
            foreach ($marks as $mark) {
                $ordered[$starts[NormalizationTables::COMBINING_CLASSES[$mark]]++] = $mark;
            }
            $this->marks = $ordered;
            return;
        }
        // Each mark goes after every mark before it of a class no higher than its own.
        $ordered = [];
        foreach ($marks as $mark) {
            $class = NormalizationTables::COMBINING_CLASSES[$mark];
            $j = count($ordered);
            while ($j > 0 && NormalizationTables::COMBINING_CLASSES[$ordered[$j - 1]] > $class) {
                $j--;
            }
            $ordered = [...array_slice($ordered, 0, $j), $mark, ...array_slice($ordered, $j)];
        }
        $this->marks = $ordered;
    }

    /**
     * Writes the starter and the first $kept marks after it, and empties the run.
     */
    private function write(int $kept): bool
    {
        if ($this->starter !== null && !$this->writeOne($this->starter)) {
            return false;
        }
        for ($i = 0; $i < $kept; $i++) {
            if (!$this->writeOne($this->marks[$i])) {
                return false;
            }
        }
        $this->starter = null;
        $this->marks = [];
        return true;
    }

    private function writeOne(string $character): bool
    {
        if ($this->longest >= 0 && $this->written >= $this->longest) {
            return false;
        }
        $this->out .= $character;
        $this->written++;
        return true;
    }

    private static function isHangulSyllable(int $cp): bool
    {
        return $cp >= self::S_BASE && $cp < self::S_BASE + self::S_COUNT;
    }

    /**
     * The primary composite of $starter followed by $character, or null where the pair does not
     * compose: Hangul's L+V and LV+T, or the table.
     */
    private static function compose(string $starter, string $character): ?string
    {
        // Every Hangul jamo and syllable is three bytes, led by E1 or by EA to ED.
        $lead = ord($starter[0]);
        if (strlen($starter) === 3 && strlen($character) === 3 && ($lead === 0xE1 || $lead >= 0xEA && $lead <= 0xED)) {
            $s = Utf8::decode($starter);
            $c = Utf8::decode($character);
            if ($s >= self::L_BASE && $s < self::L_BASE + self::L_COUNT
                && $c >= self::V_BASE && $c < self::V_BASE + self::V_COUNT) {
                return Utf8::encode(self::S_BASE + (($s - self::L_BASE) * self::V_COUNT + ($c - self::V_BASE)) * self::T_COUNT);
            }
            if (self::isHangulSyllable($s) && ($s - self::S_BASE) % self::T_COUNT === 0
                && $c > self::T_BASE && $c < self::T_BASE + self::T_COUNT) {
                return Utf8::encode($s + ($c - self::T_BASE));
            }
        }
        return NormalizationTables::COMPOSITIONS[$starter . $character] ?? null;
    }
}
