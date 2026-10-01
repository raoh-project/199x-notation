package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alphabet a machine is made deterministic over is the classes its sets cut the symbols into,
 * and not the runs their ranges leave.
 *
 * <p>Two symbols are in one class where every set holds both or neither, and two classes are told
 * apart by some set: no class is split where nothing tells its symbols apart, and none holds two
 * symbols a set tells apart. The classes are numbered by their least symbol and hold no surrogate.
 * A machine's image is written by walking the classes in that order, so it is the image the runs
 * gave, character for character.
 */
class TheClassesAMachineTellsApartAreItsAlphabetTest {

    /**
     * Every symbol is held by every set as the least symbol of its class is, and any two classes
     * are told apart by some set, for sets that overlap, nest and touch at random.
     */
    @Test
    void aClassIsWhatNoSetTellsApartAndNothingMore() {
        Random random = new Random(15);
        for (int trial = 0; trial < 40; trial++) {
            List<int[]> sets = new ArrayList<>();
            for (int each = 0; each < 1 + random.nextInt(6); each++) {
                sets.add(randomSet(random));
            }
            SymbolPartition partition = Objects.requireNonNull(SymbolPartition.of(sets, roomy()));
            Map<Integer, boolean[]> seen = new java.util.HashMap<>();
            int[] least = new int[partition.count()];
            Arrays.fill(least, -1);
            for (int symbol = 0; symbol < 0x400; symbol++) {
                int of = classOf(partition, symbol);
                if (least[of] < 0) {
                    least[of] = symbol;
                }
                for (int[] set : sets) {
                    assertEquals(holds(set, partition.least(of)), holds(set, symbol));
                }
            }
            for (int one = 0; one < partition.count(); one++) {
                boolean[] said = said(sets, partition.least(one));
                boolean[] before = seen.put(Arrays.hashCode(said), said);
                assertTrue(before == null || !Arrays.equals(before, said),
                        "class " + one + " is told apart from every other");
                if (one > 0) {
                    assertTrue(partition.least(one - 1) < partition.least(one), "numbered by least symbol");
                }
                for (int set = 0; set < sets.size(); set++) {
                    assertEquals(holds(sets.get(set), partition.least(one)),
                            Arrays.binarySearch(partition.classesOf(set), one) >= 0);
                }
            }
            for (int set = 0; set < sets.size(); set++) {
                int[] held = partition.classesOf(set);
                for (int at = 1; at < held.length; at++) {
                    assertTrue(held[at - 1] < held[at], "the classes a set holds are each once, ascending");
                }
            }
        }
    }

    /** What a set holds is each class once, in order, however many runs of it the set has. */
    @Test
    void aSetOfScatteredCharactersIsOneClassAndTheRestIsAnother() {
        int[] scattered = new int[6000];
        for (int i = 0; i < 3000; i++) {
            scattered[i * 2] = 0x20000 + 2 * i;
            scattered[i * 2 + 1] = 0x20000 + 2 * i;
        }
        SymbolPartition partition = Objects.requireNonNull(SymbolPartition.of(List.of(scattered), roomy()));
        assertEquals(2, partition.count());
        assertArrayEquals(new int[] {1}, partition.classesOf(0));
        assertEquals(0x20000, partition.least(1));
    }

    /** No class holds a surrogate, whichever sets are given. */
    @Test
    void noClassHoldsASurrogate() {
        SymbolPartition partition = Objects.requireNonNull(SymbolPartition.of(
                List.of(new int[] {0, 0xD7FF, 0xE000, 0x10FFFF}, new int[] {0xD700, 0xD7FF}), roomy()));
        assertEquals(2, partition.count());
        for (CodePoints each : Objects.requireNonNull(partition.classes(roomy()))) {
            for (CodePoints.Range range : each.ranges()) {
                assertTrue(range.to() < 0xD800 || range.from() > 0xDFFF, each.toString());
            }
        }
        assertEquals(CodePoints.EVERYTHING, Objects.requireNonNull(partition.classes(roomy())).get(0).or(Objects.requireNonNull(partition.classes(roomy())).get(1)));
    }

    /**
     * The image of a deterministic machine is the one the runs gave: the classes are walked in the
     * order their first run was, and a step is over all a class's runs, gathered as they were.
     */
    @Test
    void anImageIsTheOneTheRunsGave() {
        List<String> cases = List.of(
                "[ace]x|[bdf]y", "P1,1,8,3,0,96,103,55295,57344,1114111,3,97,97,99,99,101,101,3,98,98,100,100,102,102,2,0,55295,57344,1114111,3,0,119,121,55295,57344,1114111,1,120,120,3,0,120,122,55295,57344,1114111,1,121,121,5,0,3,0,1,1,2,2,3,0,0,1,3,1,0,0,2,4,1,5,4,0,0,2,6,1,7,4,0,1,1,3,1,0",
                "[a-cx-z]+[0-9]*", "P1,1,6,4,0,96,100,119,123,55295,57344,1114111,2,97,99,120,122,2,0,55295,57344,1114111,5,0,47,58,96,100,119,123,55295,57344,1114111,1,48,57,3,0,47,58,55295,57344,1114111,4,0,2,0,1,1,2,0,0,1,2,1,0,1,3,3,1,1,2,4,3,0,1,2,5,1,4,3,0",
                "(a|b|c)*d", "P1,1,4,1,97,99,3,0,96,101,55295,57344,1114111,1,100,100,2,0,55295,57344,1114111,3,0,3,0,0,1,1,2,2,0,0,1,3,1,0,1,1,3,1,0",
                "[あいう]{1,3}", "P1,1,3,5,0,12353,12355,12355,12357,12357,12359,55295,57344,1114111,3,12354,12354,12356,12356,12358,12358,2,0,55295,57344,1114111,5,0,2,0,1,1,2,0,0,1,2,1,0,1,2,0,1,1,3,0,1,2,0,1,1,4,0,1,1,2,1,0",
                "[^,]*,[a-zぁ-ん]*", "P1,1,5,3,0,43,45,55295,57344,1114111,1,44,44,2,97,122,12353,12435,4,0,96,123,12352,12436,55295,57344,1114111,2,0,55295,57344,1114111,3,0,2,0,0,1,1,0,1,2,2,1,3,2,0,0,1,4,2,0",
                ".*a.{3}", "P1,1,4,7,0,9,11,12,14,96,98,132,134,8231,8234,55295,57344,1114111,4,10,10,13,13,133,133,8232,8233,1,97,97,2,0,55295,57344,1114111,17,0,3,0,0,1,1,2,2,0,0,1,3,1,0,0,3,1,1,0,3,2,4,0,0,3,1,1,0,5,2,6,0,0,3,1,1,0,7,2,8,0,0,3,1,1,0,9,2,10,0,0,3,1,1,0,11,2,12,0,0,3,1,1,0,13,2,14,0,0,3,1,1,0,15,2,16,0,1,3,0,0,1,1,2,2,0,1,3,1,1,0,3,2,4,0,1,3,1,1,0,5,2,6,0,1,3,1,1,0,7,2,8,0,1,3,1,1,0,9,2,10,0,1,3,1,1,0,11,2,12,0,1,3,1,1,0,13,2,14,0,1,3,1,1,0,15,2,16,0",
                "[\\x{1F600}\\x{1F602}]+(é|😀)", "P1,1,7,4,0,55295,57344,128511,128513,128513,128515,1114111,2,128512,128512,128514,128514,2,0,55295,57344,1114111,5,0,232,234,55295,57344,128511,128513,128513,128515,1114111,1,128514,128514,1,233,233,1,128512,128512,5,0,2,0,1,1,2,0,0,1,2,1,0,0,4,3,1,4,2,5,3,6,4,0,1,1,2,1,0,1,4,3,1,4,2,5,3,6,4,0");
        for (int at = 0; at < cases.size(); at += 2) {
            PatternMachine machine = PatternMachine.of(
                    ((PatternRead.Read) PatternParser.read(cases.get(at))).meaning());
            PatternImage.Written image = assertInstanceOf(PatternImage.Written.class, machine.image());
            assertEquals(List.of(cases.get(at + 1)), image.strings(), cases.get(at));
        }
    }

    /**
     * What a machine does not accept is made over the classes too, a step a class: a wide class is
     * two steps out of a state and not thousands, and the strings it accepts are the others.
     */
    @Test
    void whatAMachineDoesNotAcceptIsMadeOverItsClasses() {
        StringBuilder wide = new StringBuilder("[");
        for (int i = 0; i < 3000; i++) {
            wide.appendCodePoint(0x20000 + 2 * i);
        }
        Automaton shaped = Automaton.of(((PatternRead.Read) PatternParser.read(wide + "]+x")).meaning(),
                Held.roomy());
        Automaton not = Objects.requireNonNull(Objects.requireNonNull(shaped).not(Held.roomy()));
        for (int state = 0; state < not.size(); state++) {
            assertTrue(not.stepsFrom(state).size() <= 3, "a step a class");
        }
        String in = new StringBuilder().appendCodePoint(0x20000).appendCodePoint(0x20002).append('x').toString();
        String out = new StringBuilder().appendCodePoint(0x20001).append('x').toString();
        assertEquals(true, shaped.accepts(in, Held.roomy()));
        assertFalse(not.walks(in));
        assertEquals(false, shaped.accepts(out, Held.roomy()));
        assertTrue(not.walks(out));
        assertTrue(not.walks(""));
    }

    /** Steps over equal sets are over one set, however the machine was written out. */
    @Test
    void equalSetsInAMachineAreOneSet() {
        CodePoints one = CodePoints.between('a', 'c');
        CodePoints other = CodePoints.between('a', 'b').or(CodePoints.of('c'));
        Automaton machine = Automaton.madeOf(List.of(
                List.of(new Automaton.Step(one, 1)),
                List.of(new Automaton.Step(other, 0))), new java.util.BitSet());
        assertTrue(machine.stepsFrom(0).get(0).over() == machine.stepsFrom(1).get(0).over());
    }

    private static Meter.Making roomy() {
        return Held.roomy().making();
    }

    /** Up to four runs below 0x400, at random. */
    private static int[] randomSet(Random random) {
        java.util.TreeSet<Integer> ends = new java.util.TreeSet<>();
        for (int each = 0; each < 2 + 2 * random.nextInt(4); each++) {
            ends.add(random.nextInt(0x400));
        }
        List<Integer> sorted = new ArrayList<>(ends);
        if (sorted.size() % 2 == 1) {
            sorted.removeLast();
        }
        int[] pairs = new int[sorted.size()];
        for (int at = 0; at < sorted.size(); at += 2) {
            pairs[at] = sorted.get(at);
            pairs[at + 1] = sorted.get(at + 1);
        }
        // Touching ends would read as one run, which is not what a set is held as.
        for (int at = 2; at < pairs.length; at += 2) {
            if (pairs[at] <= pairs[at - 1] + 1) {
                return new int[] {pairs[0], pairs[1]};
            }
        }
        return pairs;
    }

    private static int classOf(SymbolPartition partition, int symbol) {
        int piece = 0;
        while (partition.from(piece + 1) <= symbol) {
            piece++;
        }
        return partition.classOf(piece);
    }

    private static boolean[] said(List<int[]> sets, int symbol) {
        boolean[] out = new boolean[sets.size()];
        for (int at = 0; at < out.length; at++) {
            out[at] = holds(sets.get(at), symbol);
        }
        return out;
    }

    private static boolean holds(int[] pairs, int symbol) {
        for (int at = 0; at < pairs.length; at += 2) {
            if (pairs[at] <= symbol && symbol <= pairs[at + 1]) {
                return true;
            }
        }
        return false;
    }
}
