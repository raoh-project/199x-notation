<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal\Pattern;

use Raoh\Notation199x\Internal\PatternAlphabetTables;
use Raoh\Notation199x\Internal\Ranges;
use Raoh\Notation199x\Internal\Utf8;
use Raoh\Notation199x\PatternBeyond;
use Raoh\Notation199x\PatternLimit;
use Raoh\Notation199x\PatternRefusal;
use Raoh\Notation199x\PatternRefused;

/**
 * The reader of the pattern language, and the only one: what it hands on is the meaning, and
 * nothing after it reads the text again.
 *
 * @internal
 */
final class Reader
{
    /** What peek answers past the last byte, outside every value a byte has. */
    private const END_OF_TEXT = -1;

    private int $at = 0;
    private int $depth = 0;
    /** Where the construct being read begins, which is what a refusal quotes. */
    private int $construct = 0;
    /** The first limit met in the text, or null while none has been. */
    private ?PatternBeyond $past = null;
    private readonly int $length;
    /** The pattern as it is written, as it is read. */
    private readonly Tree $written;

    private function __construct(private readonly string $text)
    {
        $this->length = strlen($text);
        $this->written = new Tree(new SymbolSets());
    }

    /**
     * What $text means as a pattern, or what makes it no pattern, or which limit it is past.
     */
    public static function read(string $text): Meaning|PatternRefused|PatternBeyond
    {
        return (new self($text))->answer();
    }

    private function answer(): Meaning|PatternRefused|PatternBeyond
    {
        try {
            $w = $this->pattern();
        } catch (Refusal $refused) {
            $to = min($this->length, max($refused->to, $refused->from));
            return new PatternRefused($refused->why, $refused->from, substr($this->text, $refused->from, $to - $refused->from));
        }
        // The states are counted on what was written, where an anchor is one state whatever it
        // comes to, so the count is never below the states of the machine the meaning builds; and
        // only of a pattern within the nesting depth, since the count recurses.
        $states = $this->past === null ? States::written($this->written, $w) : 0;
        // Every anchor has to come to something, and what it comes to is settled by where it
        // stands, which is known now that the whole of the pattern is.
        if ($this->written->anchors > 0 && !Anchors::place($this->written, $w)) {
            return new PatternRefused(PatternRefusal::AnAnchorThisCannotPlace, 0, $this->text);
        }
        // The text is a pattern. Whether it is one every implementation takes is asked now.
        if ($this->past !== null) {
            return $this->past;
        }
        if ($states > PatternLimit::MachineStates->most()) {
            return new PatternBeyond(PatternLimit::MachineStates, 0, $this->text);
        }
        $this->written->sets->seal();
        return new Meaning($this->written, $w);
    }

    /**
     * The whole text, as what it is written as. A choice is read with a stack of the choices open
     * around it, so a group is a push and its closing bracket a pop, and nothing here recurses.
     *
     * The stack is two flat lists and not an object a group: text may open as many groups as it
     * has characters before it is past the nesting depth, and is read to its end all the same.
     * $items holds, for each choice open, the arms read so far and then the parts of the arm being
     * read; $opened holds, for each, where its items begin and how many of them are arms.
     */
    private function pattern(): int
    {
        /** @var list<int> $items */
        $items = [];
        /** @var list<int> $opened */
        $opened = [0, 0];
        while (true) {
            $next = $this->peek();
            if ($next !== self::END_OF_TEXT && $next !== 0x7C && $next !== 0x29) {
                $this->construct = $this->at;
                if ($next === 0x28) {
                    $this->opened();
                    array_push($opened, count($items), 0);
                } else {
                    $this->part($items, $this->quantified($this->atom()));
                }
                continue;
            }
            $top = count($opened) - 2;
            $partsFrom = $opened[$top] + $opened[$top + 1];
            if ($next === 0x7C) {
                $this->take();
                $arm = $this->arm($items, $partsFrom);
                $items[] = $arm;
                $opened[$top + 1]++;
                continue;
            }
            $arm = $this->arm($items, $partsFrom);
            $items[] = $arm;
            // A choice of one arm is that arm.
            $choice = $opened[$top + 1] === 0
                ? array_pop($items)
                : $this->written->taken(Tree::EITHER_OF, $items, $opened[$top]);
            if ($top === 0) {
                if (!$this->done()) {
                    // A bracket closing nothing, which is what is left when the reading of a
                    // choice stops before the end.
                    $this->construct = $this->at;
                    $this->take();
                    $this->refuse(PatternRefusal::SomethingUnclosed);
                }
                return $choice;
            }
            $this->expect(0x29);
            $this->depth--;
            array_pop($opened);
            array_pop($opened);
            $this->construct = $this->at;
            $this->part($items, $this->quantified($choice));
        }
    }

    /**
     * The arm whose parts are $items from $from on, taken off it: an arm of one part is that part,
     * and an arm of none is nothing. The parts go from $items into the tree as they are, and are
     * never a list of their own: an arm may be as long as the text.
     *
     * @param list<int> $items
     */
    private function arm(array &$items, int $from): int
    {
        return match (count($items) - $from) {
            0 => $this->written->leaf(Tree::NOTHING),
            1 => array_pop($items) ?? throw new \LogicException('a part counted and not held'),
            default => $this->written->taken(Tree::IN_TURN, $items, $from),
        };
    }

    /**
     * Puts $part at the end of the arm being read. A group of nothing is nothing, and is left out
     * so that one written pattern has one tree. An anchor is not one of those: where it stands
     * decides what it comes to.
     *
     * @param list<int> $items
     */
    private function part(array &$items, int $part): void
    {
        if ($this->written->kind[$part] !== Tree::NOTHING) {
            $items[] = $part;
        }
    }

    /**
     * Reads a group's opening, plain or (?:, which are the two the grammar has.
     */
    private function opened(): void
    {
        $this->expect(0x28);
        if ($this->peek() === 0x3F) {
            $this->take();
            // (?: and nothing else. A lookaround and a named group have no spelling in the grammar,
            // and a flag group would change what a class means for the rest of the pattern.
            if ($this->peek() !== 0x3A) {
                $this->take();
                $this->refuse(PatternRefusal::AGroupTheGrammarDoesNotHave);
            }
            $this->take();
        }
        $this->depth++;
        if ($this->depth > PatternLimit::NestingDepth->most()) {
            // The group that went past it, from its bracket to where its reading stopped.
            $this->beyond(PatternLimit::NestingDepth, $this->construct, $this->at);
        }
    }

    /**
     * $one with the count written after it, if any.
     */
    private function quantified(int $one): int
    {
        $this->construct = $this->at;
        switch ($this->peek()) {
            case 0x3F: // ?
                $this->take();
                [$least, $most] = [0, 1];
                break;
            case 0x2A: // *
                $this->take();
                [$least, $most] = [0, Tree::NO_CEILING];
                break;
            case 0x2B: // +
                $this->take();
                [$least, $most] = [1, Tree::NO_CEILING];
                break;
            case 0x7B: // {
                $this->take();
                $floor = $this->count();
                $ceiling = $floor;
                if ($this->peek() === 0x2C) {
                    $this->take();
                    $ceiling = $this->peek() === 0x7D ? null : $this->count();
                }
                $this->expect(0x7D);
                // Compared as written, since either may be past what a count is held at.
                if ($ceiling !== null && self::below($ceiling, $floor)) {
                    $this->refuse(PatternRefusal::ACountThisCannotRead);
                }
                $least = $floor[1];
                $most = $ceiling === null ? Tree::NO_CEILING : $ceiling[1];
                break;
            default:
                return $one;
        }
        // Reluctant says how a matcher walks and not which strings are accepted, so the marker is
        // read and left out. Possessive is not one of those: it takes what it can and gives none
        // of it back, so which strings it accepts follows from how a matcher walks.
        if ($this->peek() === 0x3F) {
            $this->take();
        } elseif ($this->peek() === 0x2B) {
            $this->take();
            $this->refuse(PatternRefusal::APossessiveRepetition);
        }
        return $this->written->repeated($one, $least, $most);
    }

    /**
     * One thing written, other than a group.
     */
    private function atom(): int
    {
        switch ($this->peek()) {
            case 0x5B: // [
                $this->take();
                return $this->written->symbols($this->characterClass());
            case 0x5C: // \
                $this->take();
                return $this->written->symbols($this->escaped());
            case 0x2E: // .
                $this->take();
                return $this->written->symbols(Symbols::DOT);
            case 0x5E: // ^
            case 0x24: // $
                $end = $this->peek() === 0x24;
                $this->take();
                return $this->written->leaf($end ? Tree::END : Tree::START);
            case 0x7B: // {
                // A brace that begins no count. Read as an ordinary character it would be a
                // pattern meaning one thing here and a count wherever a digit followed it.
                $this->take();
                $this->refuse(PatternRefusal::ACountThisCannotRead);
                // no break: refuse throws
            case 0x2A: // *
            case 0x2B: // +
            case 0x3F: // ?
                $this->take();
                $this->refuse(PatternRefusal::SomethingUnclosed);
                // no break: refuse throws
            case self::END_OF_TEXT:
                $this->refuse(PatternRefusal::SomethingUnclosed);
        }
        return $this->written->symbols(Symbols::one($this->literal()));
    }

    /**
     * What is between [ and ], as the symbols it holds. The [ is already taken.
     *
     * @return list<int>
     */
    private function characterClass(): array
    {
        $negated = $this->peek() === 0x5E;
        if ($negated) {
            $this->take();
        }
        // Gathered and put in order once, so that a class does not cost the square of its length.
        $members = [];
        $first = true;
        while (!$this->done() && ($this->peek() !== 0x5D || $first)) {
            $first = false;
            $this->construct = $this->at;
            $this->refuseClassOfClasses();
            array_push($members, ...$this->classMember());
        }
        $this->expect(0x5D);
        $held = Symbols::normalized($members);
        if ($held === []) {
            $this->refuse(PatternRefusal::SomethingUnclosed);
        }
        // The universe less what is written. A negated class does not leave out the line
        // terminators, which is why . is written as a difference of its own.
        return $negated ? Symbols::not($held) : $held;
    }

    /**
     * A [ or && here is refused, as a class inside a class and as an intersection, wherever it
     * stands in a class, an end of a run included.
     */
    private function refuseClassOfClasses(): void
    {
        if ($this->peek() === 0x5B) {
            $this->take();
            $this->refuse(PatternRefusal::AClassOfClasses);
        }
        if (substr($this->text, $this->at, 2) === '&&') {
            $this->at += 2;
            $this->refuse(PatternRefusal::AClassOfClasses);
        }
    }

    /**
     * One member of a class: a symbol, a run of them, or a shorthand's whole set. A - makes a run
     * only between two single symbols, each a character or an escape that stands for one. Anywhere
     * else it is a symbol of its own: [a-\d] holds a, - and the digits.
     *
     * @return list<int>
     */
    private function classMember(): array
    {
        $member = $this->classAtom();
        if (Symbols::size($member) === 1 && $this->peek() === 0x2D && $this->at + 1 < $this->length
            && $this->text[$this->at + 1] !== ']') {
            $this->take();
            $afterDash = $this->at;
            $this->refuseClassOfClasses();
            $upper = $this->classAtom();
            if (Symbols::size($upper) !== 1) {
                $this->at = $afterDash;
                return Symbols::normalized([...$member, 0x2D, 0x2D]);
            }
            if ($upper[0] < $member[0]) {
                $this->refuse(PatternRefusal::ACountThisCannotRead);
            }
            return Symbols::between($member[0], $upper[0]);
        }
        return $member;
    }

    /**
     * @return list<int>
     */
    private function classAtom(): array
    {
        if ($this->peek() === 0x5C) {
            $this->take();
            return $this->escaped();
        }
        return Symbols::one($this->literal());
    }

    /**
     * What an escape stands for, as symbols. The backslash is already taken.
     *
     * @return list<int>
     */
    private function escaped(): array
    {
        if ($this->done()) {
            $this->refuse(PatternRefusal::AnEscapeThisDoesNotRead);
        }
        // The whole character after the backslash, so that one past the basic plane is classified
        // as the character it is.
        [$kind] = Utf8::scalarAt($this->text, $this->at);
        switch ($kind) {
            // The shorthands, as the language defines them: the digits are the ten ASCII ones, a
            // word character is ASCII with the underscore, and the whitespace is six characters.
            case 0x64: // d
                $this->take();
                return Symbols::DIGIT;
            case 0x44: // D
                $this->take();
                return Symbols::NOT_DIGIT;
            case 0x77: // w
                $this->take();
                return Symbols::WORD;
            case 0x57: // W
                $this->take();
                return Symbols::NOT_WORD;
            case 0x73: // s
                $this->take();
                return Symbols::SPACE;
            case 0x53: // S
                $this->take();
                return Symbols::NOT_SPACE;
            case 0x6E: // n
                $this->take();
                return Symbols::one(0x0A);
            case 0x74: // t
                $this->take();
                return Symbols::one(0x09);
            case 0x72: // r
                $this->take();
                return Symbols::one(0x0D);
            case 0x66: // f
                $this->take();
                return Symbols::one(0x0C);
            case 0x61: // a
                $this->take();
                return Symbols::one(0x07);
            case 0x65: // e
                $this->take();
                return Symbols::one(0x1B);
            case 0x30: // 0
                $this->take();
                return Symbols::one($this->octal());
            case 0x78: // x
                $this->take();
                return Symbols::one($this->spelled(self::hexEscape($this->text, $this->at)));
            case 0x75: // u
                $this->take();
                return Symbols::one($this->spelled(self::unicodeEscape($this->text, $this->at)));
            case 0x70: // p
            case 0x50: // P
                $this->refuseAfter(PatternRefusal::ACharacterProperty);
                // no break: refuse throws
            case 0x62: // b
            case 0x42: // B
            case 0x41: // A
            case 0x7A: // z
            case 0x5A: // Z
            case 0x47: // G
            case 0x52: // R
                $this->refuseAfter(PatternRefusal::ABoundary);
                // no break: refuse throws
            case 0x51: // Q
            case 0x45: // E
                $this->refuseAfter(PatternRefusal::AQuotation);
                // no break: refuse throws
            case 0x6B: // k
            case 0x31:
            case 0x32:
            case 0x33:
            case 0x34:
            case 0x35:
            case 0x36:
            case 0x37:
            case 0x38:
            case 0x39:
                $this->refuseAfter(PatternRefusal::ABackReference);
        }
        // An escaped literal: \. \+ \\ \-. A letter or a decimal digit with no meaning is refused
        // rather than read as itself: read as itself, one given a meaning later would change which
        // strings an old pattern accepts.
        if ($kind >= 0 && Ranges::has(PatternAlphabetTables::LETTERS_AND_DIGITS, $kind)) {
            $this->refuseAfter(PatternRefusal::AnEscapeThisDoesNotRead);
        }
        return Symbols::one($this->literal());
    }

    /**
     * Refuses the escape whose kind is the character here, quoting it with that character whole.
     */
    private function refuseAfter(PatternRefusal $why): never
    {
        $this->take();
        $this->refuse($why);
    }

    /**
     * The symbol a \x or \u escape spells, the reading moved past it. A \u pair is the one
     * character it encodes: read as two symbols, 𐀀 would name the two halves and not
     * U+10000.
     *
     * @param array{int, int}|null $spelled the symbol and where the escape ends, or null where it
     *                                      spells none
     */
    private function spelled(?array $spelled): int
    {
        if ($spelled === null) {
            $this->refuse(PatternRefusal::AnEscapeThisDoesNotRead);
        }
        $this->at = $spelled[1];
        return $this->symbol($spelled[0]);
    }

    /**
     * A code point as a symbol, the reading already moved past what wrote it. Every character a
     * pattern names comes through here, however it was written. A surrogate is no symbol, since no
     * text holds one, and is refused.
     */
    private function symbol(int $codePoint): int
    {
        if (Symbols::isSurrogate($codePoint)) {
            $this->refuse(PatternRefusal::ACharacterNoStringHolds);
        }
        return $codePoint;
    }

    /**
     * Reads \0n, \0nn or \0mnn: up to three octal digits after the zero, up to 377.
     */
    private function octal(): int
    {
        $value = 0;
        $digits = 0;
        while ($digits < 3 && $this->peek() >= 0x30 && $this->peek() <= 0x37) {
            $value = $value * 8 + ($this->take() - 0x30);
            $digits++;
        }
        if ($digits === 0 || $value > 0xFF) {
            $this->refuse(PatternRefusal::AnEscapeThisDoesNotRead);
        }
        return $value;
    }

    /**
     * The symbol written here, a whole scalar value. Bytes that are not UTF-8 are no character.
     */
    private function literal(): int
    {
        if ($this->done()) {
            $this->refuse(PatternRefusal::SomethingUnclosed);
        }
        // ASCII, which most of a pattern is, is its own scalar value and no surrogate.
        $byte = ord($this->text[$this->at]);
        if ($byte < 0x80) {
            $this->at++;
            return $byte;
        }
        [$written, $width] = Utf8::scalarAt($this->text, $this->at);
        $this->at += $width;
        if ($written < 0) {
            $this->refuse(PatternRefusal::ACharacterNoStringHolds);
        }
        return $this->symbol($written);
    }

    /**
     * Reads a repetition's count. Every digit is read, and a count past the limit is noted and held
     * at one more than it, which is all that is asked of its value; the digits are kept so that a
     * floor and a ceiling are compared as they are written.
     *
     * @return array{string, int} its digits without leading zeros, and the value it is held at
     */
    private function count(): array
    {
        $from = $this->at;
        $most = PatternLimit::RepetitionCount->most();
        $value = 0;
        while ($this->peek() >= 0x30 && $this->peek() <= 0x39) {
            $value = min($most + 1, $value * 10 + ($this->take() - 0x30));
        }
        if ($this->at === $from) {
            $this->refuse(PatternRefusal::ACountThisCannotRead);
        }
        if ($value > $most) {
            $this->beyond(PatternLimit::RepetitionCount, $from, $this->at);
        }
        $digits = ltrim(substr($this->text, $from, $this->at - $from), '0');
        return [$digits === '' ? '0' : $digits, $value];
    }

    /**
     * Whether $count is a smaller number than $other, compared as written.
     *
     * @param array{string, int} $count
     * @param array{string, int} $other
     */
    private static function below(array $count, array $other): bool
    {
        if (strlen($count[0]) !== strlen($other[0])) {
            return strlen($count[0]) < strlen($other[0]);
        }
        return strcmp($count[0], $other[0]) < 0;
    }

    /**
     * Notes the first limit met in the text, which is the answer if the text turns out to be a
     * pattern.
     */
    private function beyond(PatternLimit $limit, int $from, int $to): void
    {
        $this->past ??= new PatternBeyond($limit, $from, substr($this->text, $from, $to - $from));
    }

    private function done(): bool
    {
        return $this->at >= $this->length;
    }

    /**
     * The byte here, or END_OF_TEXT past the last one. Read as a byte rather than as a symbol,
     * because what the grammar branches on is punctuation, all of it ASCII.
     *
     * @phpstan-impure
     */
    private function peek(): int
    {
        return $this->done() ? self::END_OF_TEXT : ord($this->text[$this->at]);
    }

    /**
     * Moves past the character here, whole, and answers its first byte.
     *
     * @phpstan-impure
     */
    private function take(): int
    {
        if ($this->done()) {
            $this->refuse(PatternRefusal::SomethingUnclosed);
        }
        $first = ord($this->text[$this->at]);
        $this->at += $first < 0x80 ? 1 : Utf8::scalarAt($this->text, $this->at)[1];
        return $first;
    }

    private function expect(int $c): void
    {
        if ($this->peek() !== $c) {
            // What is missing is a closing, and where it was looked for is what an author is sent
            // to.
            $this->construct = $this->at;
            $this->refuse(PatternRefusal::SomethingUnclosed);
        }
        $this->take();
    }

    /**
     * Refuses the construct being read, quoting it from where it began to where the reading
     * stopped.
     */
    private function refuse(PatternRefusal $why): never
    {
        throw new Refusal($why, $this->construct, $this->at);
    }

    /**
     * What \u spells, read from $at, just past the u: four hex digits, and where they are a high
     * surrogate followed by a \u escape of a low one, the one character the two encode. Null where
     * there are not four hex digits. A high escape with no low one after it spells the high
     * surrogate, which no text holds and the reader refuses.
     *
     * @return array{int, int}|null the symbol and where the escape ends
     */
    private static function unicodeEscape(string $text, int $at): ?array
    {
        $first = self::fixedHex($text, $at, 4);
        if ($first === null) {
            return null;
        }
        $next = $at + 4;
        if ($first >= 0xD800 && $first <= 0xDBFF && substr($text, $next, 2) === '\\u') {
            $second = self::fixedHex($text, $next + 2, 4);
            if ($second !== null && $second >= 0xDC00 && $second <= 0xDFFF) {
                return [0x10000 + (($first - 0xD800) << 10) + ($second - 0xDC00), $next + 6];
            }
        }
        return [$first, $next];
    }

    /**
     * What \x spells, read from $at, just past the x: two hex digits, or any number of them in
     * braces up to U+10FFFF. Null where it is neither.
     *
     * @return array{int, int}|null the symbol and where the escape ends
     */
    private static function hexEscape(string $text, int $at): ?array
    {
        if (($text[$at] ?? '') !== '{') {
            $value = self::fixedHex($text, $at, 2);
            return $value === null ? null : [$value, $at + 2];
        }
        $value = 0;
        $digits = 0;
        $here = $at + 1;
        $length = strlen($text);
        while ($here < $length && $text[$here] !== '}') {
            $digit = self::hexDigit(ord($text[$here]));
            if ($digit < 0) {
                return null;
            }
            $value = $value * 16 + $digit;
            $digits++;
            if ($value > Symbols::LAST) {
                return null;
            }
            $here++;
        }
        if ($here >= $length || $digits === 0) {
            return null;
        }
        return [$value, $here + 1];
    }

    /**
     * The $digits hex digits at $at as a number, or null where they are not there.
     */
    private static function fixedHex(string $text, int $at, int $digits): ?int
    {
        if ($at + $digits > strlen($text)) {
            return null;
        }
        $value = 0;
        for ($i = $at; $i < $at + $digits; $i++) {
            $digit = self::hexDigit(ord($text[$i]));
            if ($digit < 0) {
                return null;
            }
            $value = $value * 16 + $digit;
        }
        return $value;
    }

    /**
     * The value of an ASCII hex digit, in either case, or -1 where $c is none. A fullwidth digit is
     * no digit to the language.
     */
    private static function hexDigit(int $c): int
    {
        return match (true) {
            $c >= 0x30 && $c <= 0x39 => $c - 0x30,
            $c >= 0x41 && $c <= 0x46 => $c - 0x41 + 10,
            $c >= 0x61 && $c <= 0x66 => $c - 0x61 + 10,
            default => -1,
        };
    }
}
