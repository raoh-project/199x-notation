<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

/**
 * What makes text no pattern of the language, told apart by what an author wrote. The first three
 * are text that is no pattern at all. The rest are text that would be a pattern in some other
 * language and is not one in this, each for a reason of its own.
 */
enum PatternRefusal
{
    /**
     * A bracket, brace or parenthesis with nothing closing it, a class with nothing in it, or a
     * repetition with nothing before it to repeat.
     */
    case SomethingUnclosed;
    /**
     * A repetition whose count is no count: one with no digits, a ceiling below its floor, or a
     * run whose end comes before its start. A count past PatternLimit::RepetitionCount is a count,
     * and is PatternBeyond.
     */
    case ACountThisCannotRead;
    /** An escape with no meaning, or one with nothing after it. */
    case AnEscapeThisDoesNotRead;
    /**
     * A character no text holds: half of a surrogate pair written by its number, \uD800 on its own
     * or \x{DC00}, or bytes in the text of the pattern that are not UTF-8.
     */
    case ACharacterNoStringHolds;
    /** A group beginning (? other than (?: — a lookahead, a lookbehind, a named group, a flag group. */
    case AGroupTheGrammarDoesNotHave;
    /**
     * A reference back to what another part of the pattern matched, which can denote a set no
     * regular language is.
     */
    case ABackReference;
    /**
     * A property of a character, \p{Alpha} or \P{...}. The language names symbols by their numbers
     * and has nothing to ask a property with.
     */
    case ACharacterProperty;
    /**
     * \b, \B, \A, \z, \Z, \G or \R. The grammar has ^ and $ for the ends and nothing else that
     * stands between characters.
     */
    case ABoundary;
    /** \Q ... \E, which turns off the reading of what is inside it. */
    case AQuotation;
    /** A class inside a class, or classes joined by &&. */
    case AClassOfClasses;
    /**
     * ++, *+ and the rest: a repetition that gives nothing back, whose strings follow from how a
     * matcher walks, which the language does not describe.
     */
    case APossessiveRepetition;
    /**
     * An anchor whose answer is not a property of the pattern, as in (a|)^b, where which strings
     * are accepted is settled by which arm a string took.
     */
    case AnAnchorThisCannotPlace;
}
