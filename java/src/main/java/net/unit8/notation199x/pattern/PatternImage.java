package net.unit8.notation199x.pattern;

import java.util.List;

/**
 * The machine a pattern is run as, written out as the image a {@link StringPattern} is read back
 * from, or why it was not.
 *
 * <p>For a caller that carries a machine somewhere else before it is run, as a compiler writing it
 * into a class does. A caller that runs the pattern where it reads it has no use for this, and takes
 * {@link PatternMachine#pattern} instead.
 *
 * <p>An image is bounded by the characters it is given, which is a limit of carrying a machine and
 * not of the pattern: every pattern that is read has a machine ({@link PatternMachine}), and a
 * caller that runs it where it is runs it whatever this answers.
 *
 * <p>Made by {@link PatternMachine#image} and nowhere else. An image says whether its machine is
 * one a walk is only ever in one state of, and that is a fact about the machine the image was
 * written from; a caller that could write one from any machine and say so of it would hold an image
 * no reader takes back.
 */
public sealed interface PatternImage {

    /**
     * The most characters one pattern's image may take.
     *
     * <p>Above what the largest machine {@link PatternMachine#run} builds takes where its steps are
     * over sets a pattern writes once — a repetition written out, which is what makes a shape
     * large. So what this refuses is a pattern whose sets are themselves large.
     */
    int MOST_CHARACTERS = 1 << 23;

    /** The image, as the strings it is cut into ({@link StringPattern#CHUNK}). */
    record Written(List<String> strings) implements PatternImage {

        public Written {
            strings = List.copyOf(strings);
        }

        /** The pattern the image writes, as what text is matched against. */
        public StringPattern pattern() {
            return StringPattern.of(strings);
        }
    }

    /** The machine is written in more characters than {@code most}. */
    record MoreCharacters(int most) implements PatternImage {}
}
