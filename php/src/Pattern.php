<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Internal\Pattern\Meaning;
use Raoh\Notation199x\Internal\Pattern\Reader;

/**
 * A pattern that was read, as the strings it accepts. Only Pattern::read makes one.
 *
 * Reading a pattern answers one of three things, about three different things. A Pattern is a
 * pattern of the language within the limits every implementation holds to. A PatternRefused is
 * text that is no pattern of the language, and says what in it is not. A PatternBeyond is a
 * pattern of the language written past one of those limits: every construct in it is one the
 * language has, so an author told it is not in the language would go looking for a construct that
 * is not there.
 *
 * A pattern read in part is not an answer: a tree of the constructs that were understood accepts
 * a language the author did not write.
 *
 * What a pattern denotes is defined by the specifications, not by PCRE: preg_match is not asked,
 * and a match takes time linear in the subject whatever the pattern.
 */
final class Pattern
{
    private ?Machine $machine = null;

    private function __construct(private readonly Meaning $meaning)
    {
    }

    /**
     * What $text means as a pattern, or what makes it no pattern, or which limit it is past.
     *
     * A limit is about a pattern, so it is answered only once the text is known to be one: a count
     * or a depth past its limit is noted where it is met and the reading goes on to the end, and
     * text that is no pattern anywhere in it is PatternRefused whatever limit it also went past.
     * Of the limits, the first one met in the text, left to right, is the answer, and the states
     * are counted last, of a pattern within the other two.
     *
     * The reading has no depth of its own: groups are read with a stack rather than by recursion,
     * and the anchors are placed the same way, so text nested as deeply as it is long is read to
     * its end.
     *
     * $text may be any string. Bytes in it that are not UTF-8 are
     * PatternRefusal::ACharacterNoStringHolds. Where a refusal or a limit points is in bytes.
     */
    public static function read(string $text): self|PatternRefused|PatternBeyond
    {
        $read = Reader::read($text);
        return $read instanceof Meaning ? new self($read) : $read;
    }

    /**
     * Whether the whole of $subject is one of the strings the pattern accepts.
     *
     * The subject is read a scalar value at a time, once, and never gone back over: the machine the
     * pattern means is walked as the set of states it may be in, so a match takes time linear in
     * the subject. Where a character leads from the set the walk is in is kept once it is worked
     * out, so a walk that comes to the set again with the same character looks it up, which is
     * what most characters of most subjects cost. The machine is built the first time the pattern
     * is matched. $subject may be any string, and one holding bytes that are not UTF-8 is no text
     * and is accepted by nothing.
     */
    public function matches(string $subject): bool
    {
        $this->machine ??= Machine::build($this->meaning);
        return $this->machine->matches($subject);
    }
}
