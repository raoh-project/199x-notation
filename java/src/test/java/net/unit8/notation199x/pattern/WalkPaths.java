package net.unit8.notation199x.pattern;

import java.util.function.BooleanSupplier;

/**
 * How long a match takes on each path a walk of a machine's steps goes by, measured apart.
 *
 * <pre>
 * mvn --batch-mode test-compile
 * java -cp target/classes:target/test-classes net.unit8.notation199x.pattern.WalkPaths
 * </pre>
 *
 * <p>A walk keeps no sets, keeps a new set at most characters, or looks up sets it has kept. Work
 * put in the step every walk takes for the sake of one of these is paid by the others, so each is
 * measured on its own, and a change is compared on each: one time over all three would let what
 * one path loses be hidden by what another gains. It is not a test, and is run by hand.
 */
final class WalkPaths {

    /** Where the answers go, so that the matches are not left out as answering nothing. */
    @SuppressWarnings("unused")
    private static volatile boolean sink;

    private WalkPaths() {
    }

    public static void main(String[] args) {
        // Kept nothing by its budget, so every match moves each state for every character.
        StringPattern large = pattern("(?:a?){49998}", StringPattern.Budget.DEFAULT.keeping(0));
        if (large.way() != StringPattern.Way.EVERY_STATE) {
            throw new IllegalStateException("(?:a?){49998} is walked " + large.way());
        }
        String a = "a".repeat(100);
        long without = time(() -> large.matches(a), 1);

        // The tenth character from the end is an a: a pattern that has kept nothing comes to a new
        // set at most characters of a subject at random. A pattern keeps its sets for as long as it
        // is held, so each match is of a pattern made for it, made before the time is taken.
        Automaton tenth = shaped("(?:a|b)*a(?:a|b){8}");
        StringBuilder random = new StringBuilder();
        int seed = 9;
        for (int i = 0; i < 400; i++) {
            seed = seed * 1_664_525 + 1_013_904_223;
            random.append((seed >>> 31) == 0 ? 'a' : 'b');
        }
        String subject = random.toString();
        int fresh = 2_000;
        StringPattern[] made = new StringPattern[fresh];
        long newSet = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < fresh; i++) {
                made[i] = StringPattern.of(tenth);
            }
            if (made[0].way() != StringPattern.Way.SETS_KEPT) {
                throw new IllegalStateException("(?:a|b)*a(?:a|b){8} is walked " + made[0].way());
            }
            long start = System.nanoTime();
            boolean answer = false;
            for (StringPattern each : made) {
                answer ^= each.matches(subject);
            }
            sink = answer;
            newSet = Math.min(newSet, (System.nanoTime() - start) / fresh);
        }

        // The same subject again with one pattern: every set it comes to is kept, and every step is
        // one lookup.
        StringPattern kept = StringPattern.of(tenth);
        long again = time(() -> kept.matches(subject), 1_000);

        System.out.printf("%-52s %12.2f ms%n", "without kept sets, (?:a?){49998} against 100 a",
                without / 1e6);
        System.out.printf("%-52s %12.2f us%n", "a new set kept at most characters, 400 chars",
                newSet / 1e3);
        System.out.printf("%-52s %12.2f us%n", "kept sets looked up again, 400 chars", again / 1e3);
    }

    private static Automaton shaped(String pattern) {
        PatternRead.Read read = (PatternRead.Read) PatternParser.read(pattern);
        return PatternMachine.of(read.meaning()).shaped();
    }

    private static StringPattern pattern(String pattern, StringPattern.Budget budget) {
        return StringPattern.of(shaped(pattern), budget);
    }

    /** The least time one call of {@code match} took, as the mean of {@code batch} calls, over
     *  rounds of a second after a second of warming up. */
    private static long time(BooleanSupplier match, int batch) {
        boolean answer = false;
        long least = Long.MAX_VALUE;
        long until = System.nanoTime() + 2_000_000_000L;
        long warm = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < until) {
            long start = System.nanoTime();
            for (int i = 0; i < batch; i++) {
                answer ^= match.getAsBoolean();
            }
            long each = (System.nanoTime() - start) / batch;
            if (start > warm) {
                least = Math.min(least, each);
            }
        }
        sink = answer;
        return least;
    }
}
