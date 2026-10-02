<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

/**
 * One pass of canonical ordering and, where the form composes, composition over characters
 * already decomposed: the starter of the run it is in, the marks held after it, and what is
 * settled. A character is held as its UTF-8 bytes throughout, which is what the tables are keyed
 * by; only Hangul, which is arithmetic, is taken to its code point.
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

    /** How many marks a run may hold before they are put in order by class rather than by insertion, which is quadratic in the run. */
    private const FEW_MARKS = 32;

    /**
     * Each character's full decomposition, canonical and compatibility, as it is worked out.
     * Bounded by the characters the tables name.
     *
     * @var array{0: array<string, list<string>>, 1: array<string, list<string>>}
     */
    private static array $decompositions = [[], []];

    private string $out;
    private ?string $starter = null;
    /** @var list<string> */
    private array $marks = [];

    public function __construct(
        private readonly bool $composes,
        private readonly int $longest,
        string $kept,
        private int $written,
    ) {
        $this->out = $kept;
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
    public function take(string $character): bool
    {
        if (isset(NormalizationTables::COMBINING_CLASSES[$character])) {
            $this->marks[] = $character;
            return true;
        }
        $kept = $this->settle();
        if ($this->composes && $this->starter !== null && $kept === 0) {
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
     * What was written, the run being read settled and written after it, or null where that
     * passes the bound.
     */
    public function finish(): ?string
    {
        return $this->write($this->settle()) ? $this->out : null;
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
        if (!$this->composes || $this->starter === null) {
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
     * FEW_MARKS, which may be as long as the text, is put in order in loops of this method's own,
     * each mark into the marks of its class and the classes one after another, and is not handed
     * to a sort of PHP's.
     */
    private function order(): void
    {
        $marks = $this->marks;
        if (count($marks) > self::FEW_MARKS) {
            $byClass = [];
            foreach ($marks as $mark) {
                $byClass[NormalizationTables::COMBINING_CLASSES[$mark]][] = $mark;
            }
            $ordered = [];
            for ($class = 0; $class < 256; $class++) {
                foreach ($byClass[$class] ?? [] as $mark) {
                    $ordered[] = $mark;
                }
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
