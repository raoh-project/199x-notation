package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Half of a surrogate pair is no character, and a pattern naming one is refused with the same
 * reason whether the pattern writes it by its number or its text holds it as itself.
 *
 * <p>The text of a pattern is a {@code java.lang.String}, which holds half a pair as readily as
 * anything else. Every place the reader takes a character from that text is asked here, with each
 * half: a reader that checked only the escapes would hand the half on to a set of symbols, which
 * holds none and says so by throwing.
 */
class HalfAPairInAPatternIsRefusedHoweverItIsWrittenTest {

    private static final String HIGH = String.valueOf((char) 0xD800);
    private static final String LOW = String.valueOf((char) 0xDC00);

    /** A pattern template, {@code %s} where the character stands, and where that is in it. */
    private record Place(String pattern, int at) {}

    private static final List<Place> PLACES = List.of(
            new Place("%s", 0),             // a literal
            new Place("ab%scd", 2),         // among others
            new Place("(?:a|%s)", 5),       // in a group
            new Place("%s+", 0),            // repeated
            new Place("[%s]", 1),           // a member of a class
            new Place("[^%s]", 2),          // of a negated one
            new Place("[a%sb]", 2),
            new Place("[%s-z]", 1),         // where a run begins
            new Place("[a-%s]", 1),         // where it ends: a run is refused from where it begins
            new Place("\\%s", 0),           // after a backslash
            new Place("[\\%s]", 1));

    @Test
    void aHalfHeldAsItselfIsRefusedWhereverTheReaderTakesACharacter() {
        for (Place place : PLACES) {
            for (String half : List.of(HIGH, LOW)) {
                String pattern = place.pattern().formatted(half);
                PatternRead.Refused refused = assertInstanceOf(PatternRead.Refused.class,
                        PatternParser.read(pattern), place.pattern());
                assertEquals(PatternRead.Refusal.A_CHARACTER_NO_STRING_HOLDS, refused.why(), place.pattern());
                assertEquals(place.at(), refused.from(), place.pattern());
            }
        }
    }

    @Test
    void aLowHalfBeforeAHighOneIsTwoHalvesAndNotAPair() {
        PatternRead.Refused refused = assertInstanceOf(PatternRead.Refused.class,
                PatternParser.read("a" + LOW + HIGH));
        assertEquals(PatternRead.Refusal.A_CHARACTER_NO_STRING_HOLDS, refused.why());
        assertEquals(1, refused.from());
        assertEquals(LOW, refused.construct());
    }

    @Test
    void theSameHalfWrittenByItsNumberIsRefusedForTheSameReason() {
        for (String pattern : List.of("\\uD800", "\\uDC00", "\\x{D800}", "[\\uD800]", "[a-\\x{DFFF}]")) {
            PatternRead.Refused refused = assertInstanceOf(PatternRead.Refused.class,
                    PatternParser.read(pattern), pattern);
            assertEquals(PatternRead.Refusal.A_CHARACTER_NO_STRING_HOLDS, refused.why(), pattern);
        }
    }

    @Test
    void aWholePairIsOneCharacterHoweverItIsWritten() {
        String pair = HIGH + LOW;
        for (String pattern : List.of(pair, "[" + pair + "]", "\\uD800\\uDC00", "\\x{10000}")) {
            Held held = Held.by(pattern);
            assertEquals(true, held.has(pair), pattern);
            assertEquals(false, held.has("a"), pattern);
        }
    }

    /**
     * What is built from text that was never read as a pattern takes the text as given: it is the
     * caller's to have asked whether it is a sequence of scalar values, and half a pair is an
     * argument that is none rather than a pattern to refuse.
     */
    @Test
    void textHandedOverAsWordsIsTheCallersToHaveAsked() {
        assertThrows(IllegalArgumentException.class, () -> PatternMeaning.text("a" + HIGH));
        assertThrows(IllegalArgumentException.class,
                () -> Automaton.ofWords(List.of("a" + LOW), Held.roomy()));
    }
}
