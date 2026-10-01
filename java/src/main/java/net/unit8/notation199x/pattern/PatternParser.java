package net.unit8.notation199x.pattern;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The reader of the pattern language, and the only one.
 *
 * <p>What this reads is a pattern and what it refuses is not one. A caller asks this whether text
 * is a pattern, and what it hands on is the {@link PatternMeaning}; everything that works the
 * pattern out afterwards lowers that. None of them reads the text, so a
 * construct this learns is learned by all of them at once, and there is no second reader whose
 * answer could differ.
 *
 * <p>What it reads is also held to the limits every implementation holds to
 * ({@link PatternRead.Limit}), and a pattern past one is {@link PatternRead.Beyond}. A limit is
 * about a pattern, so it is answered only once the text is known to be one: a count or a depth past
 * its limit is noted where it is met and the reading goes on to the end, and text that is no pattern
 * anywhere in it, an anchor that cannot be placed included, is {@link PatternRead.Refused} whatever
 * limit it also went past. Of the limits, the first one met in the text, left to right, is the
 * answer, and the states are counted last, of a pattern within the other two.
 *
 * <p>So the reading has no depth of its own. Groups are read with a stack of their own rather than
 * by recursion, and the anchors are placed the same way ({@link Anchors}), so text nested as deeply
 * as it is long is read to its end and never runs a thread's stack out.
 *
 * <p>Nothing here chooses a value. What one string of the language would be is a question for
 * whatever holds the language; a reader that answered it while parsing is the arrangement that lost
 * the second arm of every choice and the ceiling of every repetition.
 */
public final class PatternParser {

    /** What {@link #peek()} answers past the last unit, outside every value a unit has. */
    private static final int END = -1;

    private final String regex;
    private int at;
    private int depth;
    /** Where the construct being read begins, which is what a refusal quotes. */
    private int construct;
    /** The first limit met in the text, or null while none has been. */
    private PatternRead.@Nullable Beyond past;

    private PatternParser(String regex) {
        this.regex = regex;
        this.at = 0;
        this.depth = 0;
        this.construct = 0;
    }

    /** What {@code regex} means, or what makes it no pattern, or which limit it is past. */
    public static PatternRead read(String regex) {
        if (regex == null) {
            throw new IllegalArgumentException("a pattern is some string");
        }
        PatternParser reader = new PatternParser(regex);
        try {
            WrittenPattern written = reader.pattern();
            // Every anchor has to come to something, and what it comes to is settled by where it
            // stands rather than by how it is written — which is known now that the whole of the
            // pattern is.
            PatternMeaning meaning = Anchors.placed(written);
            if (meaning == null) {
                return new PatternRead.Refused(PatternRead.Refusal.AN_ANCHOR_THIS_CANNOT_PLACE, 0,
                        regex);
            }
            // The text is a pattern. Whether it is one every implementation takes is asked now.
            if (reader.past != null) {
                return reader.past;
            }
            // Counted on what was written, where an anchor is one state whatever it came to, so
            // the count is never below the states of the machine the meaning builds.
            if (PatternStates.of(written) > PatternRead.Limit.MACHINE_STATES.most()) {
                return new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, regex);
            }
            return new PatternRead.Read(meaning);
        } catch (Refused refused) {
            int to = Math.min(regex.length(), Math.max(refused.to, refused.from));
            return new PatternRead.Refused(refused.why, refused.from,
                    regex.substring(refused.from, to));
        }
    }

    /**
     * The pattern {@code regex} as it is written, before its anchors are placed, for a check
     * holding what is counted from the text against what is built.
     *
     * @throws IllegalArgumentException where the text is no pattern or is past a count or a depth
     */
    static WrittenPattern writtenOf(String regex) {
        PatternParser reader = new PatternParser(regex);
        try {
            WrittenPattern written = reader.pattern();
            if (reader.past != null) {
                throw new IllegalArgumentException("past a limit: " + regex);
            }
            return written;
        } catch (Refused e) {
            throw new IllegalArgumentException("not read: " + regex);
        }
    }

    // --- the grammar ------------------------------------------------------------------------------

    /**
     * A choice being read, in a group or at the top: the arms read so far, and the parts of the one
     * being read.
     *
     * @param open    where the group's bracket is, which a refusal of it quotes; -1 at the top
     */
    private record Open(int open, List<WrittenPattern> arms, List<WrittenPattern> parts) {

        Open(int open) {
            this(open, new ArrayList<>(), new ArrayList<>());
        }

        /** The arm being read, as what it is written as. */
        WrittenPattern arm() {
            return switch (parts.size()) {
                case 0 -> new WrittenPattern.Meant(new PatternMeaning.Nothing());
                case 1 -> parts.get(0);
                default -> new WrittenPattern.InTurn(parts);
            };
        }

        /** The choice, as what it is written as. */
        WrittenPattern choice() {
            arms.add(arm());
            return arms.size() == 1 ? arms.get(0) : new WrittenPattern.EitherOf(arms);
        }
    }

    /**
     * The whole text, as what it is written as.
     *
     * <p>A choice is read with a stack of the choices open around it, so a group is a push and its
     * closing bracket a pop, and nothing here recurses. The tree is the one a recursive reading of
     * the grammar builds: an arm of one part is that part, an arm of none is nothing, a choice of one
     * arm is that arm, and a group is what is inside it.
     */
    private WrittenPattern pattern() {
        List<Open> open = new ArrayList<>();
        Open reading = new Open(-1);
        while (true) {
            if (!done() && peek() != '|' && peek() != ')') {
                construct = at;
                if (peek() == '(') {
                    opened();
                    open.add(reading);
                    reading = new Open(construct);
                } else {
                    part(reading, quantified(atom()));
                }
                continue;
            }
            if (peek() == '|') {
                take();
                reading.arms().add(reading.arm());
                reading.parts().clear();
                continue;
            }
            WrittenPattern choice = reading.choice();
            if (open.isEmpty()) {
                if (!done()) {
                    // A bracket closing nothing, which is what is left when the reading of a
                    // choice stops before the end.
                    construct = at;
                    take();
                    throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
                }
                return choice;
            }
            expect(')');
            depth--;
            reading = open.removeLast();
            construct = at;
            part(reading, quantified(choice));
        }
    }

    /** {@code one} put at the end of the arm being read. A group of nothing is nothing, and is left
     *  out so that one written pattern has one tree. An anchor is not one of those: where it stands
     *  is what decides what it comes to, so dropping it here would be answering that question with
     *  the one place that cannot see the answer. */
    private static void part(Open reading, WrittenPattern one) {
        if (!(one instanceof WrittenPattern.Meant(PatternMeaning.Nothing _))) {
            reading.parts().add(one);
        }
    }

    /** A group's opening, plain or {@code (?:}, which are the two the grammar has. */
    private void opened() {
        expect('(');
        if (peek() == '?') {
            take();
            // `(?:` and nothing else. A lookaround and a named group have no spelling in the
            // grammar, and a flag group would change what a class means for the rest of the pattern.
            if (peek() != ':') {
                take();
                throw refused(PatternRead.Refusal.A_GROUP_THE_GRAMMAR_DOES_NOT_HAVE);
            }
            take();
        }
        if (++depth > PatternRead.Limit.NESTING_DEPTH.most()) {
            // The group that went past it, from its bracket to where its reading stopped.
            beyond(PatternRead.Limit.NESTING_DEPTH, construct, at);
        }
    }

    /** {@code one} with the count written after it, if any. */
    private WrittenPattern quantified(WrittenPattern one) {
        int least;
        int most;
        construct = at;
        switch (peek()) {
            case '?' -> { take(); least = 0; most = 1; }
            case '*' -> { take(); least = 0; most = PatternMeaning.Repeated.NO_CEILING; }
            case '+' -> { take(); least = 1; most = PatternMeaning.Repeated.NO_CEILING; }
            case '{' -> {
                take();
                Count floor = count();
                // Null where the repetition has no ceiling: `{n,}`.
                @Nullable Count ceiling = floor;
                if (peek() == ',') {
                    take();
                    ceiling = peek() == '}' ? null : count();
                }
                expect('}');
                // Compared as written, since either may be past what a count is held at.
                if (ceiling != null && ceiling.below(floor)) {
                    throw refused(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ);
                }
                least = floor.held();
                most = ceiling == null ? PatternMeaning.Repeated.NO_CEILING : ceiling.held();
            }
            default -> {
                return one;
            }
        }
        // Reluctant says how a matcher walks and not which strings are accepted: it takes as few
        // copies as it can and takes more where the rest of the pattern needs them, so what is
        // matched whole is matched either way. The marker is read and left out of what this holds.
        if (peek() == '?') {
            take();
        } else if (peek() == '+') {
            // Possessive is not one of those. It takes what it can and gives none of it back, so a
            // body that accepts the empty string takes it once and refuses to try again:
            // {@code (?:|a)++} matches nothing that {@code (?:|a)+} matches beyond the empty
            // string. Which strings it accepts follows from how a matcher walks, which the language
            // does not describe.
            take();
            throw refused(PatternRead.Refusal.A_POSSESSIVE_REPETITION);
        }
        return new WrittenPattern.Repeated(one, least, most);
    }

    /** One thing written, other than a group. */
    private WrittenPattern atom() {
        int c = peek();
        return switch (c) {
            case '[' -> {
                take();
                yield symbols(characterClass());
            }
            case '\\' -> {
                take();
                yield symbols(escaped());
            }
            case '.' -> {
                take();
                // Every symbol but the line terminators. Written as a difference rather than as a
                // rule of its own, so that a negated class beside it — which does not leave them
                // out — is the same algebra with a different set taken away.
                yield symbols(CodePoints.EVERYTHING.less(CodePoints.LINE_TERMINATORS));
            }
            case '^', '$' -> {
                boolean end = peek() == '$';
                take();
                yield new WrittenPattern.Anchor(end);
            }
            // A brace that begins no count. Read as an ordinary character it would be a pattern
            // meaning one thing here and a count wherever a digit followed it.
            case '{' -> {
                take();
                throw refused(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ);
            }
            case '*', '+', '?' -> {
                take();
                throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
            }
            case END -> throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
            default -> symbols(CodePoints.of(literal()));
        };
    }

    private static WrittenPattern symbols(CodePoints held) {
        return new WrittenPattern.Meant(new PatternMeaning.Symbols(held));
    }

    // --- character classes -------------------------------------------------------------------------

    /** What is between `[` and `]`, as the symbols it holds. The `[` is already taken. */
    private CodePoints characterClass() {
        boolean negated = peek() == '^';
        if (negated) {
            take();
        }
        // Gathered and put in order once. Joined one member at a time, each join would put every
        // member so far in order again, and a class would cost the square of how long it is.
        List<CodePoints.Range> members = new ArrayList<>();
        boolean first = true;
        while (!done() && (peek() != ']' || first)) {
            first = false;
            construct = at;
            if (peek() == '[') {
                take();
                throw refused(PatternRead.Refusal.A_CLASS_OF_CLASSES);
            }
            if (peek() == '&' && at + 1 < regex.length() && regex.charAt(at + 1) == '&') {
                at += 2;
                throw refused(PatternRead.Refusal.A_CLASS_OF_CLASSES);
            }
            members.addAll(classMember().ranges());
        }
        expect(']');
        CodePoints held = new CodePoints(members);
        if (held.isEmpty()) {
            throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
        }
        // The universe less what is written, and not a set of what a reader thought was left. A
        // negated class does not leave out the line terminators, which is the whole reason `.` is
        // written as its own difference.
        return negated ? held.not() : held;
    }

    /**
     * One member of a class, which is a symbol, a run of them, or a shorthand's whole set.
     *
     * <p>A run is read only where both ends are one symbol. {@code [\d-z]} names no run: what is on
     * the left of the dash is ten symbols, and there is no such thing as the range from ten symbols
     * to one.
     */
    private CodePoints classMember() {
        CodePoints member = classAtom();
        boolean isOne = member.size() == 1;
        if (isOne && peek() == '-' && at + 1 < regex.length() && regex.charAt(at + 1) != ']') {
            take();
            CodePoints upper = classAtom();
            if (upper.size() != 1) {
                throw refused(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ);
            }
            if (upper.least() < member.least()) {
                throw refused(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ);
            }
            return CodePoints.between(member.least(), upper.least());
        }
        return member;
    }

    private CodePoints classAtom() {
        if (peek() == '\\') {
            take();
            return escaped();
        }
        return CodePoints.of(literal());
    }

    // --- escapes -----------------------------------------------------------------------------------

    /** What an escape stands for, as symbols. The backslash is already taken. */
    private CodePoints escaped() {
        if (done()) {
            throw refused(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ);
        }
        // The whole character after the backslash, so that one past the basic plane is classified
        // as the character it is rather than by the first half of its pair.
        int kind = regex.codePointAt(at);
        return switch (kind) {
            // The shorthands, as the language defines them: the digits are the ten ASCII ones, a
            // word character is ASCII with the underscore, and the whitespace is six characters.
            case 'd' -> { take(); yield CodePoints.DIGITS; }
            case 'D' -> { take(); yield CodePoints.DIGITS.not(); }
            case 'w' -> { take(); yield CodePoints.WORD; }
            case 'W' -> { take(); yield CodePoints.WORD.not(); }
            case 's' -> { take(); yield CodePoints.SPACES; }
            case 'S' -> { take(); yield CodePoints.SPACES.not(); }
            case 'n' -> { take(); yield CodePoints.of('\n'); }
            case 't' -> { take(); yield CodePoints.of('\t'); }
            case 'r' -> { take(); yield CodePoints.of('\r'); }
            case 'f' -> { take(); yield CodePoints.of('\f'); }
            case 'a' -> { take(); yield CodePoints.of(0x07); }
            case 'e' -> { take(); yield CodePoints.of(0x1B); }
            case '0' -> { take(); yield CodePoints.of(octal()); }
            case 'x' -> { take(); yield CodePoints.of(spelled(PatternEscapes.hex(regex, at))); }
            case 'u' -> { take(); yield CodePoints.of(spelled(PatternEscapes.unicode(regex, at))); }
            case 'p', 'P' -> throw refusedAfter(PatternRead.Refusal.A_CHARACTER_PROPERTY);
            case 'b', 'B', 'A', 'z', 'Z', 'G', 'R' -> throw refusedAfter(PatternRead.Refusal.A_BOUNDARY);
            case 'Q', 'E' -> throw refusedAfter(PatternRead.Refusal.A_QUOTATION);
            case 'k', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                    throw refusedAfter(PatternRead.Refusal.A_BACK_REFERENCE);
            default -> {
                // An escaped literal — `\.`, `\+`, `\\`, `\-`. A letter or a decimal digit with no
                // meaning is refused rather than read as itself: read as itself, one given a
                // meaning later would change which strings an old pattern accepts.
                if (PatternAlphabet.isKeptAfterABackslash(kind)) {
                    throw refusedAfter(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ);
                }
                yield CodePoints.of(literal());
            }
        };
    }

    /** The refusal of the escape whose kind is the character here, quoting it with that
     *  character whole. */
    private Refused refusedAfter(PatternRead.Refusal why) {
        at += Character.charCount(regex.codePointAt(at));
        return refused(why);
    }

    // --- numbers and symbols -----------------------------------------------------------------------

    /**
     * The symbol a {@code \x} or {@code \\u} escape spells ({@link PatternEscapes}), the reading
     * moved past it.
     *
     * <p>A {@code \\u} pair is the one character it encodes: read as two symbols,
     * {@code \\uD800\\uDC00} would name the two halves and not U+10000, a different set of strings
     * under the same spelling.
     */
    private int spelled(PatternEscapes.@Nullable Spelled escape) {
        if (escape == null) {
            throw refused(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ);
        }
        at = escape.end();
        return symbol(escape.symbol());
    }

    /**
     * {@code codePoint} as a symbol, the reading already moved past what wrote it.
     *
     * <p>Every character a pattern names comes through here, however it was written: as itself, in
     * a class, at either end of a run, after a backslash, or by its number. A surrogate on its own
     * is no symbol, since no text holds one, and is refused. The text of a pattern is a
     * {@code java.lang.String}, which can hold half a pair, so a character written as itself is
     * asked the same as one written by its number.
     */
    private int symbol(int codePoint) {
        if (CodePoints.isSurrogate(codePoint)) {
            throw refused(PatternRead.Refusal.A_CHARACTER_NO_STRING_HOLDS);
        }
        return codePoint;
    }

    /** `\0n`, `\0nn` or `\0mnn` — up to three octal digits after the zero. */
    private int octal() {
        int value = 0;
        int digits = 0;
        while (digits < 3 && !done() && peek() >= '0' && peek() <= '7') {
            value = value * 8 + (take() - '0');
            digits++;
        }
        if (digits == 0 || value > 0xFF) {
            throw refused(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ);
        }
        return value;
    }

    /**
     * The symbol written here, which is a whole code point where the source holds a pair.
     *
     * <p>A pattern written with a character past the basic plane holds it as two units, and a reader
     * taking one unit at a time would build a language of halves. Half a pair with no other half
     * beside it is no character ({@link #symbol}).
     */
    private int literal() {
        if (done()) {
            throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
        }
        int written = regex.codePointAt(at);
        at += Character.charCount(written);
        return symbol(written);
    }

    /**
     * A count as written: its digits without leading zeros, and the value it is held at, which is
     * one past {@link PatternRead.Limit#REPETITION_COUNT} where it is past that.
     */
    private record Count(String digits, int held) {

        /** Whether this is a smaller number than {@code other}, compared as written. */
        boolean below(Count other) {
            return digits.length() != other.digits.length()
                    ? digits.length() < other.digits.length()
                    : digits.compareTo(other.digits) < 0;
        }
    }

    /**
     * A repetition's count.
     *
     * <p>Every digit is read, and a count past {@link PatternRead.Limit#REPETITION_COUNT} is noted
     * and held at one more than it, which is all that is asked of its value; the digits are kept so
     * that a floor and a ceiling are compared as they are written.
     */
    private Count count() {
        int from = at;
        long most = PatternRead.Limit.REPETITION_COUNT.most();
        long value = 0;
        while (!done() && peek() >= '0' && peek() <= '9') {
            value = Math.min(most + 1, value * 10 + (take() - '0'));
        }
        if (at == from) {
            throw refused(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ);
        }
        if (value > most) {
            beyond(PatternRead.Limit.REPETITION_COUNT, from, at);
        }
        String digits = regex.substring(from, at).replaceFirst("^0+(?=.)", "");
        return new Count(digits, (int) value);
    }

    /** Notes the first limit met in the text, which is the answer if the text turns out to be a
     *  pattern. */
    private void beyond(PatternRead.Limit limit, int from, int to) {
        if (past == null) {
            past = new PatternRead.Beyond(limit, from, regex.substring(from, to));
        }
    }

    // --- walking -----------------------------------------------------------------------------------

    private boolean done() {
        return at >= regex.length();
    }

    /**
     * The unit here, or {@link #END} past the last one. Read as a unit rather than as a symbol,
     * because what the grammar branches on is punctuation and all of it is one unit wide.
     *
     * <p>The end is a value no unit has. U+0000 is a character a pattern may write, and stands for
     * itself like any other.
     */
    private int peek() {
        return done() ? END : regex.charAt(at);
    }

    private char take() {
        if (done()) {
            throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
        }
        return regex.charAt(at++);
    }

    private void expect(char c) {
        if (peek() != c) {
            // What is missing is a closing, and where it was looked for is what an author is sent
            // to — not the construct it would have closed, which may be far behind.
            construct = at;
            throw refused(PatternRead.Refusal.SOMETHING_UNCLOSED);
        }
        take();
    }

    /** The refusal of the construct being read, quoting it from where it began to where the reading
     *  stopped. */
    private Refused refused(PatternRead.Refusal why) {
        return new Refused(why, construct, at);
    }

    /** What a pattern that is no pattern raises, carried to the one place that answers. */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient PatternRead.Refusal why;
        private final int from;
        private final int to;

        Refused(PatternRead.Refusal why, int from, int to) {
            super(null, null, false, false);
            this.why = why;
            this.from = from;
            this.to = to;
        }
    }
}
