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
 * <p>An image is: the format's marker, the kind, the sets (each a count of runs and the runs), the states, and for each
 * state whether a walk stops there, its steps as a set and a state, and its free steps.
 */
class AnImageIsHeldToWritingAMachineTest {

    /** {@code a+}: one set, two states. */
    private static final String SOUND = "P1,1,1,1,97,97,2,0,1,0,1,0,1,1,0,1,0";

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
                Map.entry("a kind that is neither", "P1,2,1,1,97,97,2,0,1,0,1,0,1,1,0,1,0"),
                Map.entry("no state to start in", "P1,1,0,0"),
                Map.entry("a step over a set there is not", "P1,1,1,1,97,97,2,0,1,1,1,0,1,1,0,1,0"),
                Map.entry("a step to a state there is not", "P1,1,1,1,97,97,2,0,1,0,2,0,1,1,0,1,0"),
                Map.entry("a free step to a state there is not", "P1,0,0,1,1,0,1,5"),
                Map.entry("a run that ends before it begins", "P1,1,1,1,98,97,1,1,0,0"),
                Map.entry("a run past the last scalar value", "P1,1,1,1,97,1114112,1,1,0,0"),
                Map.entry("a run over a surrogate", "P1,1,1,1,55296,55296,1,1,0,0"),
                Map.entry("a run across the surrogates", "P1,1,1,1,97,65535,1,1,0,0"),
                Map.entry("runs out of order", "P1,1,1,2,98,98,97,97,1,1,0,0"),
                Map.entry("runs that overlap", "P1,1,1,2,97,99,98,100,1,1,0,0"),
                Map.entry("a deterministic state stepping two ways",
                        "P1,1,2,1,97,99,1,98,100,2,0,2,0,1,1,1,0,1,0,0"),
                Map.entry("a deterministic state stepping for nothing", "P1,1,0,1,1,0,1,0"),
                Map.entry("a count of more than is left", "P1,1,1000000000"),
                Map.entry("fewer states than it counts", "P1,0,0,3,1,0,0"),
                Map.entry("something after the machine", SOUND + ",0"),
                Map.entry("nothing after the marker", "P1,"),
                Map.entry("a number that is none", "P1,1,x"));
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

    @Test
    void anImageBeginsWithItsFormat() {
        PatternMachine machine = PatternMachine.of(
                ((PatternRead.Read) PatternParser.read("[a-z]+@[a-z]+")).meaning());
        PatternImage.Written image = (PatternImage.Written) machine.image();
        assertTrue(image.strings().get(0).startsWith("P1,"), image.strings().get(0));
        assertTrue(image.pattern().matches("a@b"));
    }

    @Test
    void anImageOfAFormatThisDoesNotReadIsRefusedSayingWhichItWasAndWhichThisReads() {
        String body = SOUND.substring("P1,".length());
        Map<String, String> unread = Map.of(
                body, "begins with \"1\"",
                "P2," + body, "format \"P2\"",
                "p1," + body, "format \"p1\"",
                "P1", "ends before its machine does",
                "", "begins with \"\"",
                "x".repeat(100), "format \"" + "x".repeat(20) + "…\"");
        for (Map.Entry<String, String> each : unread.entrySet()) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> StringPattern.of(List.of(each.getKey())), each.getKey());
            assertTrue(refused.getMessage().contains(each.getValue()), refused.getMessage());
            if (!each.getKey().equals("P1")) {
                assertTrue(refused.getMessage().endsWith("this reads [P1]"), refused.getMessage());
            }
        }
    }

    /**
     * What a writer counts an image at is never less than what it writes, the marker included, and
     * more only by the two counts at the front that it takes at their widest.
     */
    @Test
    void aWriterCountsTheImageItWrites() {
        for (String regex : List.of("a", "[a-z]+@[a-z]+", ".*a.{20}", "(?:ab|cd){3,5}x?")) {
            PatternMeaning meaning = ((PatternRead.Read) PatternParser.read(regex)).meaning();
            for (boolean deterministic : new boolean[] {true, false}) {
                Automaton shaped = java.util.Objects.requireNonNull(Automaton.of(meaning, Held.roomy()));
                Automaton machine = deterministic ? shaped.canonical(Held.roomy()) : shaped;
                if (machine == null) {
                    // .*a.{20} has no deterministic machine within what a test allows.
                    continue;
                }
                StringPattern.Writer out = new StringPattern.Writer(deterministic, Long.MAX_VALUE);
                for (int state = 0; state < machine.size(); state++) {
                    out.state(machine.stopsAt(state));
                }
                for (int state = 0; state < machine.size(); state++) {
                    for (Automaton.Step each : machine.stepsFrom(state)) {
                        int[] pairs = new int[each.over().ranges().size() * 2];
                        for (int at = 0; at < each.over().ranges().size(); at++) {
                            pairs[at * 2] = each.over().ranges().get(at).from();
                            pairs[at * 2 + 1] = each.over().ranges().get(at).to();
                        }
                        out.step(state, out.set(pairs), each.to());
                    }
                    for (int to : machine.freeFrom(state)) {
                        out.free(state, to);
                    }
                }
                int written = String.join("", out.image()).length();
                long slack = out.counted() - written;
                assertTrue(slack >= 0 && slack < 2 * 11, regex + ": counted " + out.counted()
                        + " for " + written);
            }
        }
    }
}
