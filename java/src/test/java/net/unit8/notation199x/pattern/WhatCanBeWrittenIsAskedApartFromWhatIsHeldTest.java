package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A string somebody can write is a different question from a string a machine holds.
 *
 * <p>{@link Automaton#shortest} answers with what the machine holds; {@link Automaton#shortestWritten}
 * refuses to answer with a string holding a control character. A caller writing a value out wants
 * the second — a value carrying a control character is not one anybody can read back — and one
 * deciding whether a machine holds anything wants the first.
 *
 * <p>The two come apart exactly where the shortest string held is one a source cannot carry, and
 * they come apart the most where every string held is.
 */
class WhatCanBeWrittenIsAskedApartFromWhatIsHeldTest {

    private static Automaton machine(String regex) {
        PatternMeaning meaning =
                assertInstanceOf(PatternRead.Read.class, PatternParser.read(regex), regex).meaning();
        return Objects.requireNonNull(Held.canonical(meaning, Held.roomy()), regex);
    }

    @Test
    void whatCanBeWrittenIsAskedApartFromWhatIsHeld() {
        String one = String.valueOf((char) 1);

        assertEquals("a", machine("[a-z]").shortest());
        assertEquals("a", machine("[a-z]").shortestWritten());

        // Shorter and unwritable beside longer and writable: what it holds is the short one, and
        // what can be written is the long one.
        Automaton either = machine("[\\x{1}]|abc");
        // Written as a value rather than as itself: a control character pasted into a source is
        // what this whole question is about, and one sitting in the expectation would be the same
        // trap in the test.
        assertEquals(one, either.shortest());
        assertEquals("abc", either.shortestWritten());

        // And a machine of nothing else has a string and nothing to write.
        Automaton unwritable = machine("[\\x{1}-\\x{2}]+");
        assertEquals(one, unwritable.shortest());
        assertNull(unwritable.shortestWritten(),
                "every string it holds is one nobody can paste, so there is nothing to write");
    }
}
