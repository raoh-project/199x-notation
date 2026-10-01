package net.unit8.notation199x.pattern;

/**
 * What came of reading a pattern.
 *
 * <p>Three answers, and they are about three different things. {@link Read} is a pattern of the
 * language that is within the limits every implementation holds to, as what it means. {@link Refused}
 * is text that is no pattern of the language, and says what in it is not. {@link Beyond} is a pattern
 * of the language written past one of those limits: every construct in it is one the language has,
 * so an author told it is not in the language would go looking for a construct that is not there.
 *
 * <p>The limits are the same number in every implementation, and each is decided from the text
 * alone. So whether a pattern is read never depends on how a matcher for it is built, and every
 * pattern that is read has a machine ({@link PatternMachine}).
 *
 * <p>A pattern read in part is not an answer: a tree of the constructs that were understood accepts
 * a language the author did not write, and every reader downstream would be holding a set narrower
 * than the rule.
 */
public sealed interface PatternRead {

    /**
     * The whole pattern, as the strings it accepts.
     *
     * @param meaning the strings the pattern accepts
     */
    record Read(PatternMeaning meaning) implements PatternRead {

        /** Holds a meaning to being given. */
        public Read {
            if (meaning == null) {
                throw new IllegalArgumentException("a pattern that was read says what it accepts");
            }
        }
    }

    /**
     * Text that is no pattern of the language, and what stopped the reading.
     *
     * @param why       which kind of thing it is
     * @param from      where in the text the construct that stopped it begins, in chars
     * @param construct the construct as written, which is empty where the text ended before a
     *                  construct it had begun was whole
     */
    record Refused(Refusal why, int from, String construct) implements PatternRead {

        /** Holds a refusal to its reason, its construct and a place in the text. */
        public Refused {
            if (why == null || construct == null || from < 0) {
                throw new IllegalArgumentException("a pattern refused was stopped by something");
            }
        }
    }

    /**
     * A pattern of the language written past one of the limits every implementation holds to.
     *
     * <p>Not a refusal: the language has no count, depth or size past which a pattern stops being
     * one. What is past a limit is what no implementation is asked to run.
     *
     * <p>Answered only of text read to its end and found to be a pattern, its anchors placed. Text
     * that is no pattern is {@link Refused}, whatever limit it also went past.
     *
     * @param limit     which limit it is past
     * @param from      where in the text the construct that is past it begins, in chars; nought for
     *                  {@link Limit#MACHINE_STATES}, which is a fact about the whole pattern
     * @param construct the construct as written: the count, the group opened past the depth, or the
     *                  whole pattern
     */
    record Beyond(Limit limit, int from, String construct) implements PatternRead {

        /** Holds it to its limit, its construct and a place in the text. */
        public Beyond {
            if (limit == null || construct == null || from < 0) {
                throw new IllegalArgumentException("a pattern past a limit is past some limit");
            }
        }
    }

    /**
     * The limits on a pattern every implementation holds to, each the same number everywhere.
     *
     * <p>They bound what running a pattern costs, and are stated of the text so that no
     * implementation's way of running one decides which patterns it takes.
     */
    enum Limit {

        /** A count of a repetition, written in {@code {n}}, {@code {n,}} or {@code {n,m}}. */
        REPETITION_COUNT(134_217_727),

        /** Groups one inside another. */
        NESTING_DEPTH(200),

        /**
         * The states of the pattern with its repetitions written out, counted from the text as
         * {@link PatternStates} counts them.
         */
        MACHINE_STATES(250_000);

        private final int most;

        Limit(int most) {
            this.most = most;
        }

        /**
         * The most a pattern within this limit writes.
         *
         * @return the greatest count, depth or number of states within the limit
         */
        public int most() {
            return most;
        }
    }

    /**
     * What makes text no pattern of the language.
     *
     * <p>Told apart by what an author wrote. The first group is text that is no pattern at all —
     * something left open, a count or an escape with no meaning. The rest is text that would be a
     * pattern in some other language and is not one in this, each for a reason of its own: a back
     * reference can denote a set no regular language is, a possessive
     * count's strings follow from how a matcher walks, a flag would change what a class means for
     * the rest of the pattern, and the others have no spelling in the grammar. Not "denotes no set
     * of strings": a lookahead often denotes one, and a regular one at that.
     */
    enum Refusal {

        /** A bracket, brace or parenthesis with nothing closing it, a class with nothing in it, or
         *  a repetition with nothing before it to repeat. */
        SOMETHING_UNCLOSED,

        /** A repetition whose count is no count: one with no digits, a ceiling below its floor, or
         *  a run whose end comes before its start. A count past {@link Limit#REPETITION_COUNT} is
         *  a count, and is {@link Beyond}. */
        A_COUNT_THIS_CANNOT_READ,

        /** An escape with no meaning, or one with nothing after it. */
        AN_ESCAPE_THIS_DOES_NOT_READ,

        /**
         * Half of a surrogate pair — written by its number, {@code \\uD800} on its own or
         * {@code \x{DC00}}, or held as itself by the text of the pattern.
         *
         * <p>No text holds such a character, so a pattern naming one says something about text that
         * never arrives.
         */
        A_CHARACTER_NO_STRING_HOLDS,

        /** A group beginning {@code (?} other than {@code (?:} — a lookahead, a lookbehind, a named
         *  group, a flag group. None has a spelling in the grammar, and a flag would change what a
         *  class means for the rest of the pattern. */
        A_GROUP_THE_GRAMMAR_DOES_NOT_HAVE,

        /** A reference back to what another part of the pattern matched, which can denote a set no
         *  regular language is. */
        A_BACK_REFERENCE,

        /** A property of a character — {@code \p{Alpha}}, {@code \P{...}}. The language names
         *  symbols by their numbers and has nothing to ask a property with. */
        A_CHARACTER_PROPERTY,

        /** A boundary — {@code \b}, {@code \B}, {@code \A}, {@code \z}, {@code \Z}, {@code \G},
         *  {@code \R}. The grammar has {@code ^} and {@code $} for the ends and nothing else that
         *  stands between characters. */
        A_BOUNDARY,

        /** A quotation — {@code \Q ... \E} — which turns off the reading of what is inside it. */
        A_QUOTATION,

        /** A class inside a class, or classes joined by {@code &&}. */
        A_CLASS_OF_CLASSES,

        /**
         * A repetition that gives nothing back.
         *
         * <p>{@code ++}, {@code *+} and the rest. Unlike a reluctant marker, which changes the
         * order a matcher tries things and not which strings come out, a possessive one takes what
         * it can and never tries again — so a body that accepts the empty string takes it once and
         * stops. Which strings it accepts follows from that walk, which the language does not
         * describe.
         */
        A_POSSESSIVE_REPETITION,

        /**
         * An anchor whose answer is not a property of the pattern.
         *
         * <p>{@code ^} and {@code $} are read where the shape says whether everything on that side
         * of them takes a symbol or nothing on that side does. {@code (a|)^b} is neither: which
         * strings it accepts is settled by which arm a string took, and the language has no shape
         * for a set written that way.
         */
        AN_ANCHOR_THIS_CANNOT_PLACE
    }
}
