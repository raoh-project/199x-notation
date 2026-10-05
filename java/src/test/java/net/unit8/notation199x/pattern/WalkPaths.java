package net.unit8.notation199x.pattern;

/**
 * How long a match takes on each path a walk of a machine's steps goes by, measured apart.
 *
 * <pre>
 * mvn --batch-mode test-compile
 * java -cp target/classes:target/test-classes net.unit8.notation199x.pattern.WalkPaths
 * </pre>
 *
 * <p>A walk keeps no sets, keeps a new set at most characters, goes over steps from kept sets it
 * has worked out, or works a step out and finds the set it leads to among those kept by its hash.
 * Work put in the step every walk takes for the sake of one of these is paid by the others, so each
 * is measured on its own, and a change is compared on each: one time over all would let what one
 * path loses be hidden by what another gains. {@link EachTimedWalkGoesTheWayItIsNamedTest} holds
 * each path here to the way it is named. It is not a test, and is run by hand.
 */
final class WalkPaths {

    /** Where the answers go, so that the matches are not left out as answering nothing. */
    @SuppressWarnings("unused")
    private static volatile boolean sink;

    private WalkPaths() {
    }

    /** A pattern kept nothing by its budget, whose every match moves each state for every
     *  character. */
    static StringPattern large() {
        return StringPattern.of(shaped("(?:a?){49998}"), StringPattern.Budget.DEFAULT.keeping(0));
    }

    /** What {@link #large} is matched against. */
    static String as() {
        return "a".repeat(100);
    }

    /** The tenth character from the end is an a: a pattern made from this that has kept nothing
     *  comes to a new set at most characters of {@link #random}. */
    static Automaton tenth() {
        return shaped("(?:a|b)*a(?:a|b){8}");
    }

    /** 400 characters, each a or b, at random but the same on every run. */
    static String random() {
        StringBuilder random = new StringBuilder();
        int seed = 9;
        for (int i = 0; i < 400; i++) {
            seed = seed * 1_664_525 + 1_013_904_223;
            random.append((seed >>> 31) == 0 ? 'a' : 'b');
        }
        return random.toString();
    }

    public static void main(String[] args) {
        StringPattern large = large();
        String a = as();
        long without = time(() -> large.matches(a), () -> { }, 1);

        // A pattern keeps its sets for as long as it is held, so each match is of a pattern made
        // for it, made before the time is taken.
        Automaton tenth = tenth();
        String subject = random();
        StringPattern[] made = new StringPattern[2_000];
        long newSet = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < made.length; i++) {
                made[i] = StringPattern.of(tenth);
            }
            boolean answer = false;
            long start = System.nanoTime();
            for (StringPattern each : made) {
                answer ^= each.matches(subject);
            }
            newSet = Math.min(newSet, (System.nanoTime() - start) / made.length);
            sink = answer;
        }

        StringPattern kept = StringPattern.of(tenth);
        kept.matches(subject);
        long steps = time(() -> kept.matches(subject), () -> { }, 1_000);
        // The steps are forgotten before each match and out of its time.
        long found = time(() -> kept.matches(subject), kept::forgetSteps, 1);

        System.out.printf("%-52s %12.2f ms%n", "without kept sets, (?:a?){49998} against 100 a",
                without / 1e6);
        System.out.printf("%-52s %12.2f us%n", "a new set kept at most characters, 400 chars",
                newSet / 1e3);
        System.out.printf("%-52s %12.2f us%n", "steps already worked out, 400 chars", steps / 1e3);
        System.out.printf("%-52s %12.2f us%n", "kept sets found by their hash, 400 chars", found / 1e3);
    }

    private static Automaton shaped(String pattern) {
        PatternRead.Read read = (PatternRead.Read) PatternParser.read(pattern);
        return PatternMachine.of(read.meaning()).shaped();
    }

    /** The least time one call of {@code match} took, as the mean of {@code batch} calls, each batch
     *  after {@code before} and out of its time, over a second after a second of warming up. */
    private static long time(java.util.function.BooleanSupplier match, Runnable before, int batch) {
        boolean answer = false;
        long least = Long.MAX_VALUE;
        long warm = System.nanoTime() + 1_000_000_000L;
        long until = warm + 1_000_000_000L;
        while (System.nanoTime() < until) {
            before.run();
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
