package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A machine is a value: what it accepts is settled when it is made, and nothing a caller holds
 * changes it afterwards.
 *
 * <p>A machine is built on from outside this package, so whoever writes one out state by state
 * keeps the lists and the set they wrote it from, and whoever reads one is handed its rows. Were
 * either the machine's own, a caller could change which strings it accepts after it was compared,
 * made smallest or written out, and put its tables out of step with each other. And what is written
 * out is held to being a machine before it is one, so that no walk finds out later.
 */
class AMachineIsWhatItWasMadeAsTest {

    private static final Meter ROOMY = Held.roomy();

    /** The machine accepting {@code a*}, and what it was written from. */
    private record Written(List<Automaton.Step> row, List<List<Automaton.Step>> rows, BitSet accepting) {

        static Written ofAs() {
            List<Automaton.Step> row = new ArrayList<>();
            row.add(new Automaton.Step(CodePoints.of('a'), 0));
            List<List<Automaton.Step>> rows = new ArrayList<>();
            rows.add(row);
            BitSet accepting = new BitSet();
            accepting.set(0);
            return new Written(row, rows, accepting);
        }
    }

    @Test
    void whatItWasWrittenFromMayBeWrittenToAndTheMachineIsAsItWas() {
        Written written = Written.ofAs();
        Automaton machine = Automaton.madeOf(written.rows(), written.accepting());

        written.accepting().clear(0);
        written.row().clear();
        written.rows().add(new ArrayList<>());
        written.rows().add(new ArrayList<>());

        assertEquals(1, machine.size());
        assertTrue(machine.stopsAt(0));
        assertEquals(List.of(new Automaton.Step(CodePoints.of('a'), 0)), machine.stepsFrom(0));
        assertEquals(true, machine.accepts("aaa", ROOMY));
        assertEquals(false, machine.accepts("ab", ROOMY));
        // And every operation still walks it: the tables are in step with each other.
        Automaton one = machine.canonical(ROOMY);
        assertTrue(one.walks("aa"));
        assertFalse(one.not(ROOMY).walks("aa"));
    }

    @Test
    void theRowsItHandsOutCannotBeWrittenTo() {
        Written written = Written.ofAs();
        Automaton.Step other = new Automaton.Step(CodePoints.of('b'), 0);
        PatternRead.Read read = (PatternRead.Read) PatternParser.read("a[bc]*|d{2,3}");
        Automaton shaped = Automaton.of(read.meaning(), ROOMY);
        List<Automaton> machines = List.of(
                Automaton.madeOf(written.rows(), written.accepting()),
                shaped,
                shaped.canonical(ROOMY),
                shaped.canonical(ROOMY).not(ROOMY),
                shaped.or(shaped, ROOMY),
                shaped.and(shaped, ROOMY),
                Automaton.ofWords(List.of("ab", "c"), ROOMY));
        for (Automaton machine : machines) {
            for (int state = 0; state < machine.size(); state++) {
                List<Automaton.Step> row = machine.stepsFrom(state);
                assertThrows(UnsupportedOperationException.class, () -> row.add(other));
                assertThrows(UnsupportedOperationException.class, row::clear);
                int[] free = machine.freeFrom(state);
                if (free.length > 0) {
                    free[0] = -1;
                    assertTrue(machine.freeFrom(state)[0] >= 0, "what is handed out is a copy");
                }
            }
        }
    }

    @Test
    void whatIsWrittenOutIsHeldToBeingAMachine() {
        BitSet none = new BitSet();
        assertThrows(IllegalArgumentException.class, () -> Automaton.madeOf(List.of(), none),
                "no state to start in");
        assertThrows(IllegalArgumentException.class,
                () -> Automaton.madeOf(List.of(List.of(new Automaton.Step(CodePoints.of('a'), 1))), none),
                "a step to a state there is not");
        BitSet past = new BitSet();
        past.set(1);
        assertThrows(IllegalArgumentException.class, () -> Automaton.madeOf(List.of(List.of()), past),
                "stopping at a state there is not");
        assertThrows(IllegalArgumentException.class, () -> new Automaton.Step(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Automaton.Step(CodePoints.of('a'), -1));
        List<Automaton.Step> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> Automaton.madeOf(List.of(withNull), none));
    }
}
