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
            List<StringPattern> runs = new ArrayList<>(List.of(machine.pattern(),
                    StringPattern.of(shaped, false), StringPattern.of(shaped, false, 0),
                    StringPattern.of(shaped, false, 1), StringPattern.of(shaped, false, 3)));
            if (machine.deterministic() != null) {
                runs.add(StringPattern.of(machine.deterministic(), true));
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
     * The sets a pattern keeps are found by whichever thread gets to one first and read by all of
     * them, and every thread answers what the machine accepts.
     */
    @Test
    void walksOnManyThreadsAtOnceAnswerAsOneWould() throws Exception {
        Automaton shaped = PatternMachine.of(((PatternRead.Read) PatternParser.read(".*a.{12}")).meaning())
                .shaped();
        for (int round = 0; round < 5; round++) {
            StringPattern shared = StringPattern.of(shaped, false);
            ExecutorService threads = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> done = new ArrayList<>();
                for (int thread = 0; thread < 8; thread++) {
                    int seed = round * 8 + thread;
                    done.add(threads.submit(() -> {
                        Random random = new Random(seed);
                        for (int each = 0; each < 300; each++) {
                            String subject = subject(random);
                            assertEquals(shaped.accepts(subject, Held.roomy()), shared.matches(subject),
                                    shown(subject));
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
        SymbolClasses classes = SymbolClasses.of(sets);
        for (int symbol = 0; symbol <= Character.MAX_CODE_POINT; symbol++) {
            int some = classes.some(classes.of(symbol));
            for (int[] set : sets) {
                assertEquals(holds(set, some), holds(set, symbol), "U+" + Integer.toHexString(symbol));
            }
        }
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
