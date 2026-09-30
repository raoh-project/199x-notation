package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image is text, and may come from anywhere. What it says is checked when it is read, so that a
 * pattern that was read answers every text it is asked about, and what a writer writes is an image
 * that is read.
 *
 * <p>An image is: the kind, the sets (each a count of runs and the runs), the states, and for each
 * state whether a walk stops there, its steps as a set and a state, and its free steps.
 */
class AnImageIsHeldToWritingAMachineTest {

    /** {@code a+}: one set, two states. */
    private static final String SOUND = "1,1,1,97,97,2,0,1,0,1,0,1,1,0,1,0";

    @Test
    void aSoundImageIsRead() {
        StringPattern pattern = StringPattern.of(List.of(SOUND));
        assertTrue(pattern.matches("aa"));
        assertFalse(pattern.matches(""));
        assertFalse(pattern.matches("ab"));
    }

    @Test
    void anImageThatWritesNoMachineIsRefusedWhenItIsRead() {
        Map<String, String> unsound = Map.ofEntries(
                Map.entry("a kind that is neither", "2,1,1,97,97,2,0,1,0,1,0,1,1,0,1,0"),
                Map.entry("no state to start in", "1,0,0"),
                Map.entry("a step over a set there is not", "1,1,1,97,97,2,0,1,1,1,0,1,1,0,1,0"),
                Map.entry("a step to a state there is not", "1,1,1,97,97,2,0,1,0,2,0,1,1,0,1,0"),
                Map.entry("a free step to a state there is not", "0,0,1,1,0,1,5"),
                Map.entry("a run that ends before it begins", "1,1,1,98,97,1,1,0,0"),
                Map.entry("a run past the last scalar value", "1,1,1,97,1114112,1,1,0,0"),
                Map.entry("a run over a surrogate", "1,1,1,55296,55296,1,1,0,0"),
                Map.entry("a run across the surrogates", "1,1,1,97,65535,1,1,0,0"),
                Map.entry("runs out of order", "1,1,2,98,98,97,97,1,1,0,0"),
                Map.entry("runs that overlap", "1,1,2,97,99,98,100,1,1,0,0"),
                Map.entry("a deterministic state stepping two ways",
                        "1,2,1,97,99,1,98,100,2,0,2,0,1,1,1,0,1,0,0"),
                Map.entry("a deterministic state stepping for nothing", "1,0,1,1,0,1,0"),
                Map.entry("a count of more than is left", "1,1000000000"),
                Map.entry("fewer states than it counts", "0,0,3,1,0,0"),
                Map.entry("something after the machine", SOUND + ",0"),
                Map.entry("nothing at all", ""),
                Map.entry("a number that is none", "1,x"));
        for (Map.Entry<String, String> each : unsound.entrySet()) {
            assertThrows(IllegalArgumentException.class,
                    () -> StringPattern.of(List.of(each.getValue())), each.getKey());
        }
    }

    @Test
    void aWriterWritesOnlyWhatIsRead() {
        StringPattern.Writer unsorted = new StringPattern.Writer(true, 1_000);
        assertThrows(IllegalArgumentException.class, () -> unsorted.set(new int[] {'b', 'b', 'a', 'a'}));
        assertThrows(IllegalArgumentException.class, () -> unsorted.set(new int[] {0xD800, 0xD800}));

        StringPattern.Writer noSuchSet = new StringPattern.Writer(true, 1_000);
        int only = noSuchSet.state(true);
        assertThrows(IllegalArgumentException.class, () -> noSuchSet.step(only, 0, only));

        StringPattern.Writer noSuchState = new StringPattern.Writer(true, 1_000);
        int from = noSuchState.state(true);
        noSuchState.step(from, noSuchState.set(new int[] {'a', 'a'}), 7);
        assertThrows(IllegalStateException.class, noSuchState::image);

        assertThrows(IllegalStateException.class, () -> new StringPattern.Writer(false, 1_000).image());

        // A state may be stepped to before it is made.
        StringPattern.Writer ahead = new StringPattern.Writer(true, 1_000);
        int first = ahead.state(false);
        ahead.step(first, ahead.set(new int[] {'a', 'a'}), 1);
        ahead.state(true);
        assertEquals(true, StringPattern.of(ahead.image()).matches("a"));
    }
}
