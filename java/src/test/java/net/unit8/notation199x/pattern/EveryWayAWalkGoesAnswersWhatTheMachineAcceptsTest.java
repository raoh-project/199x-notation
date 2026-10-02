package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * However a walk goes over a subject, it answers what the machine accepts as
 * {@link Automaton#accepts} walks it, which is written apart from every walk.
 *
 * <p>A walk looks characters up as the classes the machine's steps tell apart, goes over a run of
 * characters that keep it where it is without looking each up, keeps the sets of states it is in
 * over a machine that is not deterministic, and moves each state for every character once it is
 * past the sets kept. None of that is in what a pattern means, so each is held here to the answer
 * of a walk that does none of it, over text of many scripts, of characters past the Basic
 * Multilingual Plane and of halves of surrogate pairs.
 */
class EveryWayAWalkGoesAnswersWhatTheMachineAcceptsTest {

    private static final List<String> PATTERNS = List.of(
            ".*", "\\w+", "[ぁ-ん]+", "[a-zぁ-ん]*x", ".*a.{5}", "(a|é|あ|😀)*", "[^,]*,[^,]*",
            "[\\x{0}-\\x{10FFFF}]*", "[^a]*", ".*😀.*", "(ab|aé)*a?", "[α-ωА-я]+.?", "[^\\x{1F600}]+",
            "(a?){8}a{8}", "[0-9]{4}-[0-9]{2}", "(.*,){3}.*", "[!-~]+", "[a-ceg-iα-γА-Вぁ-んア-ン一-龯]+", "\\d*\\D",
            "[^\\x{0}-\\x{10FFFF}]a*", "a*[^\\x{0}-\\x{10FFFF}]");

    /** Characters from runs a walk keeps to and runs past them: ASCII, Latin, Greek, Cyrillic,
     *  kana, the line terminators, the private use area, emoji, and halves of pairs. */
    private static final String[] PIECES = {"a", "b", "x", ",", "0", "9", "-", "_", " ", "é", "ÿ", "α",
            "Я", "あ", "ん", "ア", "漢", "\n", "\r", "\u0085", " ", "", "�", "😀", "😁",
            "\uD800", "\uDC00", "aaaaaaaa", "ああああ", "😀😀"};

    @Test
    void everyWalkAnswersWhatTheMachineAccepts() {
        Random random = new Random(199);
        for (String pattern : PATTERNS) {
            PatternRead.Read read = assertInstanceOf(PatternRead.Read.class, PatternParser.read(pattern), pattern);
            PatternMachine machine = PatternMachine.of(read.meaning());
            Automaton shaped = machine.shaped();
            List<StringPattern> runs = new ArrayList<>(List.of(machine.pattern()));
            runs.addAll(everyWay(shaped, false));
            if (machine.deterministic() != null) {
                runs.addAll(everyWay(machine.deterministic(), true));
                runs.addAll(everyWayOfP2(machine.deterministic()));
            }
            for (int each = 0; each < 400; each++) {
                String subject = subject(random);
                Boolean accepted = shaped.accepts(subject, Held.roomy());
                for (StringPattern run : runs) {
                    String what = pattern + " " + run + " " + shown(subject);
                    assertEquals(accepted, run.matches(subject), what);
                    assertEquals(new Outcome.Answered<>(accepted), run.matches(subject, () -> true), what);
                }
            }
        }
    }

    /**
     * What a walk stays where it is on is the same for the same classes, in whatever order they are
     * added and however often one is: walks on many threads add them to one set at once, each to
     * what the others added.
     */
    @Test
    void whatAWalkStaysOnIsTheSameForTheSameClassesHoweverAdded() {
        List<int[]> sets = new ArrayList<>();
        Random random = new Random(18);
        for (int each = 0; each < 40; each++) {
            int from = 0x80 + random.nextInt(0xD000);
            sets.add(new int[] {from, from + random.nextInt(3000)});
            int ascii = random.nextInt(120);
            sets.add(new int[] {ascii, ascii + random.nextInt(8)});
        }
        SymbolClasses classes = classes(sets, new Meter(1, 1, 5_000_000).making());
        for (int trial = 0; trial < 500; trial++) {
            List<Integer> chosen = new ArrayList<>();
            for (int each = 0; each < 1 + random.nextInt(8); each++) {
                chosen.add(random.nextInt(classes.count()));
            }
            SymbolClasses.Stay once = SymbolClasses.Stay.NONE;
            for (int each : new java.util.TreeSet<>(chosen)) {
                once = once.with(classes, each);
            }
            List<Integer> shuffled = new ArrayList<>(chosen);
            shuffled.addAll(chosen.subList(0, random.nextInt(chosen.size() + 1)));
            java.util.Collections.shuffle(shuffled, random);
            SymbolClasses.Stay again = SymbolClasses.Stay.NONE;
            for (int each : shuffled) {
                again = again.with(classes, each);
            }
            assertEquals(once, again, chosen + " added as " + shuffled);
        }
    }

    /**
     * A walk over a deterministic machine asks once before each character it reads, whichever way it
     * goes, and goes over no character without asking: the runs it goes over without looking each up
     * are for a walk that does not ask.
     */
    @Test
    void aDeterministicWalkAsksOnceACharacterWhicheverWayItGoes() {
        PatternMachine machine = PatternMachine.of(((PatternRead.Read) PatternParser.read("[a-zぁ-ん😀]*,\\d+"))
                .meaning());
        Automaton deterministic = machine.deterministic();
        String subject = "abcあいう😀😀xyz".repeat(20) + ",123";
        int characters = subject.codePointCount(0, subject.length());
        List<StringPattern> every = new ArrayList<>(everyWay(deterministic, true));
        every.addAll(everyWayOfP2(deterministic));
        for (StringPattern run : every) {
            if (run.way() == StringPattern.Way.SETS_KEPT || run.way() == StringPattern.Way.EVERY_STATE) {
                // Walked as sets of states, which asks as such a walk does.
                continue;
            }
            long[] asked = {0};
            assertEquals(new net.unit8.notation199x.Outcome.Answered<>(true),
                    run.matches(subject, () -> ++asked[0] > 0), run.way().toString());
            assertEquals(characters, asked[0], run.way().toString());
        }
    }

    /** A budget allows nothing or something of each part, and no more sets than it can look up. */
    @Test
    void aBudgetIsNoneOrSomeOfEachPart() {
        StringPattern.Budget given = StringPattern.Budget.DEFAULT;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> given.keeping(-1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> given.keeping(StringPattern.Budget.MOST_SUBSETS + 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new StringPattern.Budget(-1, 0, 0, 0, 0, 0));
        assertEquals(StringPattern.Way.EVERY_STATE, StringPattern.of(Automaton.of(
                ((PatternRead.Read) PatternParser.read("a*")).meaning(), Held.roomy()), false,
                new StringPattern.Budget(0, 0, 0, 0, 0, 0)).way());
    }

    /** {@code machine} run each way a walk may go over it, as budgets that run out where each does
     *  choose, each held to be the way it says. */
    private static List<StringPattern> everyWay(Automaton machine, boolean deterministic) {
        StringPattern.Budget given = StringPattern.Budget.DEFAULT;
        List<StringPattern> out = new ArrayList<>();
        if (deterministic) {
            out.add(way(StringPattern.Way.TABLE, machine, true, given));
            StringPattern.Budget noTable = new StringPattern.Budget(given.classWork(), 0,
                    given.asciiEntries(), given.runs(), given.subsets(), given.remembered());
            out.add(way(StringPattern.Way.ASCII_AND_RUNS, machine, true, noTable));
            out.add(way(StringPattern.Way.RUNS, machine, true, new StringPattern.Budget(
                    given.classWork(), 0, 0, given.runs(), given.subsets(), given.remembered())));
            out.add(way(StringPattern.Way.ASCII_AND_RUNS, machine, true, new StringPattern.Budget(
                    0, given.tableEntries(), given.asciiEntries(), given.runs(), given.subsets(),
                    given.remembered())));
            // Neither a table nor runs: walked as sets of states, and deterministic still.
            out.add(way(StringPattern.Way.SETS_KEPT, machine, true, new StringPattern.Budget(
                    given.classWork(), 0, given.asciiEntries(), 0, given.subsets(), given.remembered())));
            out.add(way(StringPattern.Way.EVERY_STATE, machine, true, new StringPattern.Budget(
                    0, given.tableEntries(), given.asciiEntries(), 0, given.subsets(), given.remembered())));
        } else {
            out.add(way(StringPattern.Way.SETS_KEPT, machine, false, given));
            out.add(way(StringPattern.Way.SETS_KEPT, machine, false, given.keeping(1)));
            out.add(way(StringPattern.Way.SETS_KEPT, machine, false, given.keeping(3)));
            out.add(way(StringPattern.Way.EVERY_STATE, machine, false, given.keeping(0)));
            out.add(way(StringPattern.Way.EVERY_STATE, machine, false, new StringPattern.Budget(
                    0, given.tableEntries(), given.asciiEntries(), given.runs(), given.subsets(),
                    given.remembered())));
        }
        return out;
    }

    /** {@code machine} written as an image of P2 and read back, walked over a table and, where
     *  the budget gives it none, over the spans the image writes, with and without ASCII looked up
     *  in a table of its own and with and without the classes' tables. */
    private static List<StringPattern> everyWayOfP2(Automaton machine) {
        List<String> image = ((PatternImage.Written) PatternImages.p2(machine)).strings();
        StringPattern.Budget given = StringPattern.Budget.DEFAULT;
        StringPattern.Budget noTable = new StringPattern.Budget(given.classWork(), 0,
                given.asciiEntries(), given.runs(), given.subsets(), given.remembered());
        StringPattern.Budget noAscii = new StringPattern.Budget(given.classWork(), 0, 0,
                given.runs(), given.subsets(), given.remembered());
        StringPattern.Budget noClasses = new StringPattern.Budget(0, given.tableEntries(), 0,
                given.runs(), given.subsets(), given.remembered());
        return List.of(
                wayOfP2(StringPattern.Way.TABLE, image, given),
                wayOfP2(StringPattern.Way.ASCII_AND_SPANS, image, noTable),
                wayOfP2(StringPattern.Way.SPANS, image, noAscii),
                wayOfP2(StringPattern.Way.SPANS, image, noClasses));
    }

    private static StringPattern wayOfP2(StringPattern.Way expected, List<String> image,
                                         StringPattern.Budget budget) {
        StringPattern run = StringPattern.of(image, budget);
        assertEquals(expected, run.way(), "P2 " + budget);
        return run;
    }

    private static StringPattern way(StringPattern.Way expected, Automaton machine, boolean deterministic,
                                     StringPattern.Budget budget) {
        StringPattern run = StringPattern.of(machine, deterministic, budget);
        assertEquals(expected, run.way(), budget.toString());
        return run;
    }

    /**
     * A set a walk would start in that is past what the sets kept may hold is not kept uncounted:
     * the pattern keeps none and moves each state for every character.
     */
    @Test
    void aFirstSetPastWhatMayBeKeptKeepsNone() {
        Automaton shaped = PatternMachine.of(((PatternRead.Read) PatternParser.read("(a?){50}b")).meaning())
                .shaped();
        StringPattern.Budget given = StringPattern.Budget.DEFAULT;
        StringPattern run = StringPattern.of(shaped, false, new StringPattern.Budget(given.classWork(),
                given.tableEntries(), given.asciiEntries(), given.runs(), given.subsets(), 10));
        assertEquals(StringPattern.Way.EVERY_STATE, run.way());
        assertTrue(run.matches("a".repeat(50) + "b"));
        assertFalse(run.matches("a".repeat(51) + "b"));
    }

    /**
     * Sets nested one in another cover each other's pieces, as many as the sets times the pieces:
     * working their classes out is counted, and given up where it is past what it is allowed,
     * rather than done whatever it costs.
     */
    @Test
    void classesOfSetsNestedDeeplyAreGivenUpOnTheWorkTheyTake() {
        int sets = 5_000;
        List<int[]> nested = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (int each = 0; each < sets; each++) {
            int from = 0x4E00 + each;
            int to = 0x4E00 + 2 * sets - each;
            nested.add(new int[] {from, to});
            regex.append(String.format("[\\x{%X}-\\x{%X}]", from, to));
            text.append((char) (0x4E00 + sets));
        }
        assertEquals(null, SymbolPartition.of(nested, new Meter(1, 1, 5_000_000).making()));
        assertTrue(classes(nested.subList(0, 100), new Meter(1, 1, 5_000_000).making()) != null);

        Automaton shaped = Automaton.of(((PatternRead.Read) PatternParser.read(regex.toString())).meaning(),
                Held.roomy());
        StringPattern run = StringPattern.of(shaped, false);
        assertEquals(StringPattern.Way.EVERY_STATE, run.way());
        assertTrue(run.matches(text.toString()));
        assertFalse(run.matches(text.substring(1) + "a"));
    }

    /**
     * What a pattern keeps for a faster walk, its sets and the characters each set or state stays
     * on, is found by whichever thread gets to it first and read by all of
     * them, and every thread answers what the machine accepts.
     */
    @Test
    void walksOnManyThreadsAtOnceAnswerAsOneWould() throws Exception {
        for (String pattern : List.of(".*a.{12}", "[^,]*,[a-zぁ-ん]*", "(a|é|あ|😀)*x")) {
            PatternMachine machine = PatternMachine.of(((PatternRead.Read) PatternParser.read(pattern)).meaning());
            Automaton shaped = machine.shaped();
            for (int round = 0; round < 3; round++) {
                // Made anew each round, so that what each walk keeps is found by the threads at once.
                List<StringPattern> runs = new ArrayList<>(everyWay(shaped, false));
                if (machine.deterministic() != null) {
                    runs.addAll(everyWay(machine.deterministic(), true));
                }
                ExecutorService threads = Executors.newFixedThreadPool(8);
                try {
                    List<Future<?>> done = new ArrayList<>();
                    for (int thread = 0; thread < 8; thread++) {
                        int seed = round * 8 + thread;
                        done.add(threads.submit(() -> {
                            Random random = new Random(seed);
                            for (int each = 0; each < 200; each++) {
                                String subject = subject(random);
                                Boolean accepted = shaped.accepts(subject, Held.roomy());
                                for (StringPattern run : runs) {
                                    assertEquals(accepted, run.matches(subject),
                                            pattern + " " + run.way() + " " + shown(subject));
                                }
                            }
                        }));
                    }
                    for (Future<?> each : done) {
                        each.get();
                    }
                } finally {
                    threads.shutdown();
                    assertTrue(threads.awaitTermination(1, TimeUnit.MINUTES));
                }
            }
        }
    }

    /** The classes a machine's sets cut the symbols into hold each symbol where every set holds it as
     *  it holds the symbol its class is asked by. */
    @Test
    void aClassIsHeldByEverySetAsItsSymbolIs() {
        List<int[]> sets = new ArrayList<>();
        for (String pattern : List.of("[a-z]", "\\w", ".", "[ぁ-ん]", "[^\\x{1F600}]", "[a-ceg-iα-γА-Вぁ-んア-ン一-龯]",
                "[\\x{10000}-\\x{10FFFF}]", "[\\x{FFFF}\\x{10000}]", "[\\x{0}]", "[\\x{10FFFF}]")) {
            CodePoints set = ((PatternMeaning.Symbols) ((PatternRead.Read) PatternParser.read(pattern))
                    .meaning()).held();
            int[] pairs = new int[set.ranges().size() * 2];
            for (int at = 0; at < set.ranges().size(); at++) {
                pairs[at * 2] = set.ranges().get(at).from();
                pairs[at * 2 + 1] = set.ranges().get(at).to();
            }
            sets.add(pairs);
        }
        SymbolClasses classes = classes(sets, new Meter(1, 1, 5_000_000).making());
        for (int symbol = 0; symbol <= Character.MAX_CODE_POINT; symbol++) {
            if (CodePoints.isSurrogate(symbol)) {
                assertEquals(-1, classes.of(symbol), "U+" + Integer.toHexString(symbol));
                continue;
            }
            int some = classes.some(classes.of(symbol));
            for (int[] set : sets) {
                assertEquals(holds(set, some), holds(set, symbol), "U+" + Integer.toHexString(symbol));
            }
        }
    }

    private static SymbolClasses classes(List<int[]> sets, Meter.Making making) {
        SymbolPartition partition = SymbolPartition.of(sets, making);
        return partition == null ? null : SymbolClasses.of(partition, making);
    }

    private static boolean holds(int[] pairs, int symbol) {
        for (int at = 0; at < pairs.length; at += 2) {
            if (pairs[at] <= symbol && symbol <= pairs[at + 1]) {
                return true;
            }
        }
        return false;
    }

    private static String subject(Random random) {
        StringBuilder out = new StringBuilder();
        int pieces = random.nextInt(12);
        for (int each = 0; each < pieces; each++) {
            out.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return out.toString();
    }

    private static String shown(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.chars().forEach(each -> out.append(each >= 0x20 && each < 0x7f
                ? String.valueOf((char) each) : String.format("\\u%04X", each)));
        return out.append('"').toString();
    }
}
