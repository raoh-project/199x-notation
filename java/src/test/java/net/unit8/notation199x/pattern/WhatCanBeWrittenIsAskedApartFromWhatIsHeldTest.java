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
 * refuses to answer with a string holding a character a source cannot carry as itself, which is a
 * Unicode control code, General_Category=Cc, other than TAB, LF and CR. A caller writing a value out
 * wants the second — a value carrying one of those is not one anybody can read back — and one
 * deciding whether a machine holds anything wants the first. TAB, LF and CR are control codes a
 * literal spells, so a value holding one is written.
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
        // Written as a value rather than as itself: a character pasted into a source that the
        // source cannot carry is what this whole question is about, and one sitting in the
        // expectation would be the same trap in the test.
        assertEquals(one, either.shortest());
        assertEquals("abc", either.shortestWritten());

        // And a machine of nothing else has a string and nothing to write.
        Automaton unwritable = machine("[\\x{1}-\\x{2}]+");
        assertEquals(one, unwritable.shortest());
        assertNull(unwritable.shortestWritten(),
                "every string it holds is one nobody can paste, so there is nothing to write");
    }

    /**
     * The line is the Unicode control codes less the three a literal spells: those three are
     * written, and every other control code, C0, DEL and C1 alike, is not.
     */
    @Test
    void theControlsALiteralSpellsAreWrittenAndNoOtherIs() {
        for (int spelled : new int[] {0x09, 0x0A, 0x0D}) {
            String pattern = "[\\x{" + Integer.toHexString(spelled) + "}]+";
            String value = Character.toString(spelled);
            assertEquals(value, machine(pattern).shortestWritten(), pattern);
            assertEquals(value, machine(pattern + "|abc").shortestWritten(), pattern + "|abc");
        }
        for (int control : new int[] {0x00, 0x08, 0x0B, 0x0C, 0x0E, 0x1F, 0x7F, 0x80, 0x9F}) {
            String pattern = "[\\x{" + Integer.toHexString(control) + "}]";
            assertNull(machine(pattern + "+").shortestWritten(), pattern + "+");
            assertEquals("abc", machine(pattern + "|abc").shortestWritten(), pattern + "|abc");
        }
    }

    /**
     * And the line is at the control codes' edges and not past them: the characters beside them are
     * written, NO-BREAK SPACE first after C1.
     */
    @Test
    void theCharactersBesideTheControlCodesAreWritten() {
        for (int beside : new int[] {0x20, 0x7E, 0xA0}) {
            String pattern = "[\\x{" + Integer.toHexString(beside) + "}]+";
            assertEquals(Character.toString(beside), machine(pattern).shortestWritten(), pattern);
        }
    }
}
