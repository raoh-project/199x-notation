package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Checkpoint;
import net.unit8.notation199x.Outcome;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The strings a pattern of the language accepts, as a machine a run walks: what text is matched
 * against.
 *
 * <p>No pattern text is here and none is read. {@link PatternParser} reads a pattern,
 * {@link PatternMachine} builds the machine it means, and what is run is that machine
 * ({@link PatternMachine#pattern}) or an image it was written as and read back from
 * ({@link #of}). So nothing in a match decides what a pattern means, and no engine's way of finding
 * a match is involved in the answer.
 *
 * <p>A walk reads each character of the subject once, holds no stack and never goes back. The
 * symbols are put into the classes the machine's steps tell apart ({@link SymbolClasses}), and a
 * character is looked up as its class. Where the machine is deterministic, a character is then one
 * more lookup, of where its class leads. Where it is not, the walk is in a set of states, and where
 * a class leads from a set is worked out the first time a walk needs it, by moving each state of
 * the set, a state being put in the set at most once; it is kept with the set, so a walk that comes
 * to it again looks it up as over a deterministic machine. The sets kept are bounded, and a walk
 * that would need one more goes on moving each state it is in for every character. Which machine is
 * run is {@link PatternMachine}'s choice and changes no answer: a pattern whose deterministic machine
 * is too costly to make is run as the machine its shape builds, which has the states
 * {@link PatternStates} counts and no more.
 *
 * <p>A symbol is a scalar value. Text holding half a surrogate pair is no text, and no set
 * in an image holds a surrogate, so such text is accepted by nothing.
 *
 * <p>A match can also be run with a {@link Checkpoint}, for a caller that may have to stop it part
 * of the way through ({@link #matches(String, Checkpoint)}). It asks before each character of the
 * subject it reads, and a walk whose answer is no before any character reads none. Where the walk is in a set of states and where the class of the character leads from it is
 * not yet known, which can take as many states as the machine has, it also asks before it makes the room
 * the walk works it out in, which is as large as the machine, before each state it moves, each step
 * it looks at from one, and each state and step for no character it takes the set into, and then
 * before each place it looks for the set among those kept and each state of a kept set it holds
 * against it. A walk past the sets kept asks as one that moves each state for every character
 * does: as above for each character, and before it makes its room, before each state and step for
 * no character it starts in, and, once the subject is read, before each state it ended in that it
 * looks through for one it may stop at.
 *
 * <p>That is the whole of what a match does that grows with the subject or the machine. Between two
 * asks the work is a search of a state's steps or of the classes past the Basic Multilingual Plane.
 */
public final class StringPattern implements Predicate<String> {

    /**
     * The most characters one string of an image holds.
     *
     * <p>An image is cut so that each string of it can be a constant of a class file, which holds a
     * constant string in at most this many bytes (JVMS 4.4.7): a caller that writes an image into a
     * class it generates writes each string as it is. An image is written in ASCII, which is one
     * byte a character.
     */
    public static final int CHUNK = 65_535;

    /** Whether a walk is only ever in one state: the machine was said to be, and was held to it
     *  ({@link #oneWay}). How it is walked is the {@link Budget}'s, and this is not. */
    private final boolean deterministic;

    /** For each state, whether the walk may stop there. */
    private final boolean[] accepting;

    /** For each state, whether a walk from it can still reach one it may stop at. A walk left with
     *  none of those has its answer already. */
    private final boolean[] live;

    /**
     * For each state of a deterministic machine with no {@link #table}, its steps as runs sorted by
     * where they begin: {@code from, to, target} for each. Null for any other, and where they would
     * be more than the {@link Budget} allows.
     *
     * <p>As many as the runs of the sets each state steps over, every state over again: a set many
     * states step over is held once in {@link #over} and once a state here. So they are made only
     * where a walk goes this way, and counted before they are.
     */
    private final int @Nullable [][] runs;

    /** For each state of a machine that is not deterministic, the sets its steps are over, as
     *  {@code from, to} pairs. */
    private final int[][][] over;

    /** Where each of those steps leads. */
    private final int[][] target;

    /** For each state, the states a walk is also in for no character. */
    private final int[][] free;

    /**
     * What a pattern may spend on walking faster than one state, or one set of states, a character
     * by a search of its steps. None of it decides an answer: where a part of it runs out, the walk
     * that part would have quickened is walked the slower way, and says so ({@link #way}).
     *
     * <p>Each part is about one thing a faster walk is made of, and runs out on its own: a machine
     * whose classes are too many for a table may still have its ASCII characters in one.
     *
     * @param classWork    what working out the {@link SymbolClasses} may look at
     * @param tableEntries the most entries a deterministic machine's {@link Table} holds, as many as
     *                     its states times its classes
     * @param asciiEntries the most entries a deterministic machine's {@link Ascii} table holds, as
     *                     many as its states times the kinds of ASCII character it tells apart
     * @param runs         the most {@link #runs} a deterministic machine with no table holds, as many
     *                     as the runs of the sets each of its states steps over; past them it is
     *                     walked as a machine that is not deterministic
     * @param subsets      the most sets of states a pattern keeps for its walks ({@link #remember})
     * @param remembered   the most states and steps those sets hold between them, each set's states
     *                     and one step for each class; each is an {@code int} or a reference, about
     *                     four bytes
     */
    record Budget(long classWork, int tableEntries, int asciiEntries, int runs, int subsets,
                  long remembered) {

        /** The most sets a budget may keep: the places they are looked up in are four times as
         *  many, and are made with the pattern. */
        static final int MOST_SUBSETS = 1 << 20;

        /** Holds every part to being none or some, and the sets kept to what can be looked up. */
        Budget {
            if (classWork < 0 || tableEntries < 0 || asciiEntries < 0 || runs < 0 || subsets < 0
                    || remembered < 0) {
                throw new IllegalArgumentException("a budget allows nothing or something of each part");
            }
            if (subsets > MOST_SUBSETS) {
                throw new IllegalArgumentException("a budget keeps at most " + MOST_SUBSETS + " sets");
            }
        }

        /**
         * What a pattern is given. The tables are held for as long as the pattern is: the machines
         * of the formats people write take a few hundred entries, the runs at most about twelve
         * megabytes, and the sets kept at most about four.
         */
        static final Budget DEFAULT = new Budget(5_000_000, 1 << 18, 1 << 16, 1 << 20, 2048, 1 << 20);

        /** This, keeping at most {@code subsets} sets of states. */
        Budget keeping(int subsets) {
            return new Budget(classWork, tableEntries, asciiEntries, runs, subsets, remembered);
        }
    }

    /** How a pattern walks a subject, which {@link Budget} decides and no answer depends on. */
    enum Way {
        /** One state at a time, each character a lookup of its class and of where it leads. */
        TABLE,
        /** One state at a time, an ASCII character a lookup and any other a search of the steps. */
        ASCII_AND_RUNS,
        /** One state at a time, each character a search of the steps. */
        RUNS,
        /** One set of states at a time, kept with where each class leads from it. */
        SETS_KEPT,
        /** Every state at once, each moved for each character. */
        EVERY_STATE
    }

    /** Which symbols the machine's steps tell apart, or null where working them out was past what
     *  the {@link Budget} allows. */
    private final @Nullable SymbolClasses classes;

    /**
     * For each state of a deterministic machine and each class, at {@code state * classes + class},
     * where it leads, written as that state times the classes so that the next lookup needs no
     * multiplying, or -1 where it leads nowhere or to a state from which no walk is accepted. Null
     * for a machine that is not deterministic, that has no {@link #classes}, or whose table is past
     * the {@link Budget}.
     */
    private final @Nullable Table table;

    /**
     * A deterministic machine's {@link #table}, with the classes it is as wide as, and for each state
     * that steps back to itself, the characters it does so on.
     *
     * <p>Those are worked out from the state's row the first time a walk stays in the state, by
     * whichever walk does, and kept: a walk that reads none works them out again from the same row.
     */
    private record Table(SymbolClasses classes, int[] steps, SymbolClasses.@Nullable Stay[] stays) {

        /** The characters state {@code state} steps back to itself on, {@code state} being written
         *  as in {@link #steps}. */
        SymbolClasses.Stay stay(int state) {
            int width = classes.count();
            SymbolClasses.@Nullable Stay known = stays[state / width];
            if (known == null) {
                known = SymbolClasses.Stay.NONE;
                for (int each = 0; each < width; each++) {
                    if (steps[state + each] == state) {
                        known = known.with(classes, each);
                    }
                }
                stays[state / width] = known;
            }
            return known;
        }
    }

    /** Where an ASCII character leads from each state of a deterministic machine that has no
     *  {@link #table}, or null where it has one or this would be past the {@link Budget}. */
    private final @Nullable Ascii ascii;

    /**
     * A deterministic machine's steps over ASCII, one lookup a character, for a machine whose
     * classes are too many for a {@link Table}: how many classes the whole of the symbols is cut
     * into says nothing of how few kinds of ASCII character the machine tells apart.
     *
     * <p>The characters are put into kinds first: two characters no run tells apart step every
     * state to the same state, so the table is as wide as the kinds and not as the characters.
     *
     * @param kind  for each ASCII character, the kind it is in
     * @param kinds how many kinds there are
     * @param steps for each state and kind, at {@code state * kinds + kind}, the state it leads to,
     *              or -1 where it leads nowhere or to a state from which no walk is accepted
     */
    private record Ascii(byte[] kind, int kinds, int[] steps) {}

    /** The sets of states a walk over a machine that is not deterministic has been found to be in,
     *  or null where the machine is deterministic, has no {@link #classes}, or its first set is past
     *  the {@link Budget}. */
    private final @Nullable Subsets subsets;

    private StringPattern(boolean deterministic, boolean[] accepting, int[][][] over, int[][] target,
                          int[][] free, Budget budget) {
        this.accepting = accepting;
        this.over = over;
        this.target = target;
        this.free = free;
        this.live = live(accepting, target, free);
        // Worked out from this machine's own sets, so a machine has the same classes however it was
        // come to.
        List<int[]> sets = distinct(over);
        SymbolPartition partition = null;
        SymbolClasses classes = null;
        if (budget.classWork() > 0) {
            Meter.Making making = new Meter(1, 1, budget.classWork()).making();
            partition = SymbolPartition.of(sets, making);
            classes = partition == null ? null : SymbolClasses.of(partition, making);
        }
        // Held to what it says it is before anything is made of it, and whatever the budget: the
        // budget decides how a machine is walked, and a deterministic machine with neither a table
        // nor runs within it is walked as the sets of states its steps lead to.
        if (deterministic) {
            oneWay(over, sets);
        }
        this.deterministic = deterministic;
        this.classes = classes;
        this.table = deterministic && partition != null && classes != null
                ? table(over, target, live, sets, partition, classes, budget) : null;
        this.runs = deterministic && table == null && runs(over) <= budget.runs()
                ? runs(over, target) : null;
        this.ascii = runs != null ? ascii(runs, live, budget) : null;
        this.subsets = table == null && runs == null && classes != null && budget.subsets() > 0
                ? subsets(classes, budget) : null;
    }

    /** How this pattern walks a subject. */
    Way way() {
        if (table != null) {
            return Way.TABLE;
        }
        if (runs != null) {
            return ascii != null ? Way.ASCII_AND_RUNS : Way.RUNS;
        }
        return subsets != null ? Way.SETS_KEPT : Way.EVERY_STATE;
    }

    /** Each set the steps are over, once however many steps are over it. */
    private static List<int[]> distinct(int[][][] over) {
        Map<int[], Boolean> seen = new IdentityHashMap<>();
        List<int[]> out = new ArrayList<>();
        for (int[][] sets : over) {
            for (int[] set : sets) {
                if (seen.put(set, Boolean.TRUE) == null) {
                    out.add(set);
                }
            }
        }
        return out;
    }

    /**
     * The {@link #table} of a deterministic machine, or null where it would hold more than the
     * {@link Budget} allows.
     *
     * <p>Each step fills the classes its set holds, so a set is looked at as its classes and not as
     * its runs, and a state's row is filled once each: the machine was held to stepping one way
     * before this ({@link #oneWay}).
     */
    private static @Nullable Table table(int[][][] over, int[][] target, boolean[] live,
                                         List<int[]> sets, SymbolPartition partition,
                                         SymbolClasses classes, Budget budget) {
        int width = classes.count();
        if ((long) over.length * width > budget.tableEntries()) {
            return null;
        }
        Map<int[], Integer> index = new IdentityHashMap<>();
        for (int at = 0; at < sets.size(); at++) {
            index.put(sets.get(at), at);
        }
        int[] steps = new int[over.length * width];
        Arrays.fill(steps, -1);
        for (int state = 0; state < over.length; state++) {
            for (int step = 0; step < over[state].length; step++) {
                int to = target[state][step];
                if (live[to]) {
                    for (int each : partition.classesOf(indexOf(index, over[state][step]))) {
                        steps[state * width + each] = to * width;
                    }
                }
            }
        }
        return new Table(classes, steps, new SymbolClasses.Stay[over.length]);
    }

    private static int indexOf(Map<int[], Integer> index, int[] set) {
        Integer at = index.get(set);
        if (at == null) {
            throw new IllegalStateException("every set a step is over is one of the machine's sets");
        }
        return at;
    }

    /**
     * Holds a machine said to be deterministic to stepping one way for each character out of every
     * state, or throws {@link IllegalArgumentException} naming a state that steps two ways.
     *
     * <p>Asked of the sets alone, and not of any table a walk is given, so whether a machine is the
     * one it says it is never turns on a {@link Budget}. A state stepping over fewer than two sets
     * steps one way. Of the rest, states that step over the same sets are asked about once: a
     * machine whose every state steps over one wide class and the rest is asked once. And the sets
     * of a state are held against each other without going over the widest of them: each run of the
     * others is looked for in it by a search. So what this looks at is, once for each different
     * group of sets a state steps over, the runs of all but the widest of them.
     *
     * <p>That is not bounded by the image. A set is written once however many states step over it,
     * and holding sets against one another in groups no two of which are alike is, in the worst
     * case, more than the image is long; an image of a deterministic machine has always been held to
     * this, and every image an earlier release wrote has to be read.
     */
    private static void oneWay(int[][][] over, List<int[]> sets) {
        Map<int[], Integer> index = new IdentityHashMap<>();
        for (int at = 0; at < sets.size(); at++) {
            index.put(sets.get(at), at);
        }
        java.util.Set<Group> asked = new java.util.HashSet<>();
        for (int state = 0; state < over.length; state++) {
            int[][] mine = over[state];
            if (mine.length < 2) {
                continue;
            }
            int[] group = new int[mine.length];
            for (int step = 0; step < mine.length; step++) {
                group[step] = indexOf(index, mine[step]);
            }
            Arrays.sort(group);
            if (!asked.add(new Group(group))) {
                continue;
            }
            int widest = 0;
            for (int at = 1; at < group.length; at++) {
                if (group[at] == group[at - 1] && sets.get(group[at]).length > 0) {
                    throw twoWays(state, sets.get(group[at])[0]);
                }
                if (sets.get(group[at]).length > sets.get(group[widest]).length) {
                    widest = at;
                }
            }
            // The runs of the others in order, which overlap where one begins before the one before
            // it ends; and each looked for in the widest.
            int[] wide = sets.get(group[widest]);
            List<int[]> others = new ArrayList<>();
            for (int at = 0; at < group.length; at++) {
                int[] set = sets.get(group[at]);
                if (at != widest) {
                    for (int run = 0; run < set.length; run += 2) {
                        others.add(new int[] {set[run], set[run + 1]});
                    }
                }
            }
            others.sort((one, other) -> Integer.compare(one[0], other[0]));
            for (int at = 0; at < others.size(); at++) {
                int[] run = others.get(at);
                if (at > 0 && run[0] <= others.get(at - 1)[1]) {
                    throw twoWays(state, run[0]);
                }
                int met = firstEndingFrom(wide, run[0]);
                if (met >= 0 && wide[met] <= run[1]) {
                    throw twoWays(state, Math.max(run[0], wide[met]));
                }
            }
        }
    }

    /** The sets a state steps over, by where each is among the machine's sets, in order. */
    private record Group(int[] sets) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Group it && Arrays.equals(sets, it.sets);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(sets);
        }

        @Override
        public String toString() {
            return Arrays.toString(sets);
        }
    }

    /** Where in {@code ranges}, as {@code from, to} pairs, the first run ending at or after
     *  {@code symbol} begins, or -1 where none does. */
    private static int firstEndingFrom(int[] ranges, int symbol) {
        int low = 0;
        int high = ranges.length / 2;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ranges[mid * 2 + 1] < symbol) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low < ranges.length / 2 ? low * 2 : -1;
    }

    /** That a machine said to be deterministic steps two ways for {@code symbol} out of
     *  {@code state}. */
    private static IllegalArgumentException twoWays(int state, int symbol) {
        return new IllegalArgumentException("a deterministic machine steps one way for a character,"
                + " and state " + state + " steps two ways for " + symbol);
    }

    /** The {@link Ascii} table of a deterministic machine, or null where it would hold more than the
     *  {@link Budget} allows. */
    private static @Nullable Ascii ascii(int[][] runs, boolean[] live, Budget budget) {
        // A kind begins at 0 and wherever a run begins or ends inside ASCII.
        boolean[] begins = new boolean[ASCII + 1];
        begins[0] = true;
        for (int[] each : runs) {
            for (int at = 0; at < each.length; at += 3) {
                if (each[at] < ASCII) {
                    begins[each[at]] = true;
                }
                if (each[at + 1] + 1 < ASCII) {
                    begins[each[at + 1] + 1] = true;
                }
            }
        }
        byte[] kind = new byte[ASCII];
        int kinds = 0;
        for (int c = 0; c < ASCII; c++) {
            if (begins[c]) {
                kinds++;
            }
            kind[c] = (byte) (kinds - 1);
        }
        if ((long) runs.length * kinds > budget.asciiEntries()) {
            return null;
        }
        // Each kind is asked by its first character, which steps every state as the rest of it does.
        int[] first = new int[kinds];
        for (int c = ASCII - 1; c >= 0; c--) {
            first[kind[c]] = c;
        }
        int[] steps = new int[runs.length * kinds];
        for (int state = 0; state < runs.length; state++) {
            for (int each = 0; each < kinds; each++) {
                int to = next(runs[state], first[each]);
                steps[state * kinds + each] = (to >= 0 && live[to]) ? to : -1;
            }
        }
        return new Ascii(kind, kinds, steps);
    }

    /** The {@link #subsets} of a machine that is not deterministic, holding the one a walk starts
     *  in: the first state and every live one it reaches for no character. Null where that set is
     *  past what the sets kept may hold, and every walk moves each state for every character. */
    private @Nullable Subsets subsets(SymbolClasses classes, Budget budget) {
        Room room = new Room(accepting.length);
        room.round = 1;
        int count = close(0, room.there, 0, room, null);
        return Subsets.of(classes, budget, room, count);
    }

    /**
     * Whether the whole of {@code value} is one of the strings.
     *
     * @param value the text
     * @return whether the whole of it is accepted
     */
    public boolean matches(String value) {
        return run(value, null);
    }

    /**
     * {@link #matches(String)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * @param value      the text
     * @param checkpoint asked before each character, state and step the walk looks at, as the class
     *                   says
     * @return whether the whole of {@code value} is accepted, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before that was found
     */
    public Outcome<Boolean> matches(String value, Checkpoint checkpoint) {
        try {
            return new Outcome.Answered<>(run(value, checkpoint));
        } catch (Stop stop) {
            return new Outcome.Stopped<>();
        }
    }

    /**
     * A walk's {@link Checkpoint} said not to go on: thrown where it was asked, and caught by
     * {@link #matches(String, Checkpoint)}, which answers {@link Outcome.Stopped}.
     *
     * <p>The package's own rules keep theirs out of sight in that package, which this one cannot see
     * into; what each promises a caller is one promise, which {@link Checkpoint} states.
     */
    private static final class Stop extends RuntimeException {

        private static final Stop STOP = new Stop();

        private Stop() {
            super(null, null, false, false);
        }
    }

    /**
     * Asks {@code checkpoint}, where there is one, whether to go on, and throws {@link Stop} where
     * not.
     *
     * <p>Here and not on {@link Stop}: a compiler that leaves the methods of an exception's class
     * out of the code that calls them would otherwise make every character of a walk a call.
     */
    private static void ask(@Nullable Checkpoint checkpoint) {
        if (checkpoint != null && !checkpoint.proceed()) {
            throw Stop.STOP;
        }
    }

    @Override
    public boolean test(String value) {
        return matches(value);
    }

    /**
     * The formats an image is written in, each named by the marker an image begins with.
     *
     * <p>An image is written by one copy of this library and read by another: a compiler writes it
     * into a class, and the class runs against whichever release the program resolves. So a format
     * that a release has written is read by every release after it, and one is added here and none
     * is taken away. Which format is written is {@link #WRITTEN_FORMAT}, apart from this, so that
     * writing a new format does not stop the old ones being read.
     *
     * <p>A marker is written into images and outlives every name in this code, so it is a value of
     * its own and not the constant's name: renaming a constant changes nothing an image says. A
     * marker holds no comma, which ends it, and is not a number, which is how an image without a
     * marker would begin. And no two formats have one marker, or an image of one would be read as
     * the other: the formats are looked up by marker in {@link #BY_MARKER}, which is where that is
     * held.
     *
     * <p>That every format has a reader is held by the compiler: {@link StringPattern#of} switches
     * over the formats with no default.
     */
    private enum ImageFormat {

        /** The kind, the sets, and the states with their steps, as numbers between commas. */
        P1("P1");

        private final String marker;

        ImageFormat(String marker) {
            if (marker.isEmpty() || marker.indexOf(',') >= 0
                    || marker.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalStateException("a format's marker is neither empty, nor holds a"
                        + " comma, nor is a number: \"" + marker + "\"");
            }
            this.marker = marker;
        }

        /** What an image of this format begins with, before its first comma. */
        String marker() {
            return marker;
        }

        /** Each format by its marker, made once the formats are, and refusing two with one. */
        private static final Map<String, ImageFormat> BY_MARKER = byMarker();

        private static Map<String, ImageFormat> byMarker() {
            Map<String, ImageFormat> out = new LinkedHashMap<>();
            for (ImageFormat each : values()) {
                ImageFormat had = out.putIfAbsent(each.marker, each);
                if (had != null) {
                    throw new IllegalStateException("two image formats, " + had.name() + " and "
                            + each.name() + ", have the marker \"" + each.marker + "\"");
                }
            }
            return Map.copyOf(out);
        }

        /** Every marker this reads, for saying so. */
        static List<String> markers() {
            return Arrays.stream(values()).map(ImageFormat::marker).toList();
        }

        /** The format {@code marker} names, or why there is none this reads. */
        static ImageFormat named(String marker) {
            ImageFormat format = BY_MARKER.get(marker);
            if (format != null) {
                return format;
            }
            String shown = marker.length() > 20 ? marker.substring(0, 20) + "…" : marker;
            if (marker.isEmpty() || marker.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("an image begins with the format it is written in,"
                        + " and this one begins with \"" + shown + "\"; this reads "
                        + markers());
            }
            throw new IllegalArgumentException("an image written in format \"" + shown
                    + "\", and this reads " + markers());
        }
    }

    /** The format a {@link Writer} writes. Not what is read, which is every {@link ImageFormat}. */
    private static final ImageFormat WRITTEN_FORMAT = ImageFormat.P1;

    /**
     * The pattern {@code image} writes.
     *
     * <p>The image begins with the format it is written in, and is read as that format. An image of
     * a format this release does not read, or of none, is refused saying which it was given and
     * which this reads: every format an earlier release wrote is one of them.
     *
     * <p>An image is held to writing a machine before there is a pattern: a state to start in, sets
     * that are runs of scalar values in order, steps over sets it has to states it has, and in a
     * deterministic machine no symbol leading two ways. An image is text and may come from
     * anywhere, so what it says is checked here, once, and a pattern that was read answers every
     * text it is asked about. Anything else is an {@link IllegalArgumentException}.
     *
     * <p>Whether a deterministic machine leads one way is asked of its sets, whatever a walk over it
     * is given to walk faster on ({@link #oneWay}).
     *
     * @param image the image, as the strings it was cut into
     * @return the pattern it writes
     */
    public static StringPattern of(List<String> image) {
        String text = String.join("", image);
        int comma = text.indexOf(',');
        ImageFormat format = ImageFormat.named(comma < 0 ? text : text.substring(0, comma));
        Ints in = new Ints(text, comma < 0 ? text.length() : comma + 1);
        return switch (format) {
            case P1 -> readP1(in);
        };
    }

    /** The machine an image of {@link ImageFormat#P1} writes, read from after its marker. */
    private static StringPattern readP1(Ints in) {
        boolean deterministic = in.flag("which kind of machine it is");
        int[][] sets = new int[in.count()][];
        for (int i = 0; i < sets.length; i++) {
            int[] ranges = new int[in.count() * 2];
            for (int at = 0; at < ranges.length; at++) {
                ranges[at] = in.next();
            }
            sets[i] = aSet(ranges);
        }
        int states = in.count();
        if (states == 0) {
            throw new IllegalArgumentException("a machine has a state to start in");
        }
        boolean[] accepting = new boolean[states];
        int[][][] over = new int[states][][];
        int[][] target = new int[states][];
        int[][] free = new int[states][];
        for (int state = 0; state < states; state++) {
            accepting[state] = in.flag("whether a walk stops at a state");
            int steps = in.count();
            over[state] = new int[steps][];
            target[state] = new int[steps];
            for (int step = 0; step < steps; step++) {
                over[state][step] = sets[in.below(sets.length, "set")];
                target[state][step] = in.below(states, "state");
            }
            free[state] = new int[in.count()];
            for (int at = 0; at < free[state].length; at++) {
                free[state][at] = in.below(states, "state");
            }
            if (deterministic && free[state].length > 0) {
                throw new IllegalArgumentException(
                        "a deterministic machine steps nowhere for no character");
            }
        }
        if (!in.done()) {
            throw new IllegalArgumentException("an image holds one machine and nothing after it");
        }
        return new StringPattern(deterministic, accepting, over, target, free, Budget.DEFAULT);
    }

    /**
     * What {@code machine} accepts, run as it is: the machine a pattern is run as, held here without
     * being written into an image and read back.
     *
     * <p>For a caller that runs a pattern where it reads it, which has nowhere to carry an image to.
     * The machine is one this package built, so what an image is checked for on the way in holds
     * of it already, and a set a machine steps over twice is held once.
     *
     * @param machine       the machine
     * @param deterministic whether it is one where a walk is only ever in one state, which has no
     *                      step for no character
     */
    static StringPattern of(Automaton machine, boolean deterministic) {
        return of(machine, deterministic, Budget.DEFAULT);
    }

    /** {@link #of(Automaton, boolean)}, given {@code budget} to walk faster on, so that each
     *  {@link Way} a walk goes can be run where the one it would be given is another. */
    static StringPattern of(Automaton machine, boolean deterministic, Budget budget) {
        int states = machine.size();
        boolean[] accepting = new boolean[states];
        int[][][] over = new int[states][][];
        int[][] target = new int[states][];
        int[][] free = new int[states][];
        // A set is held once in a machine however many steps are over it, so it is written out once.
        Map<CodePoints, int[]> sets = new IdentityHashMap<>();
        for (int state = 0; state < states; state++) {
            accepting[state] = machine.stopsAt(state);
            List<Automaton.Step> steps = machine.stepsFrom(state);
            over[state] = new int[steps.size()][];
            target[state] = new int[steps.size()];
            for (int step = 0; step < steps.size(); step++) {
                over[state][step] = sets.computeIfAbsent(steps.get(step).over(), StringPattern::pairs);
                target[state][step] = steps.get(step).to();
            }
            free[state] = machine.freeFrom(state).clone();
            if (deterministic && free[state].length > 0) {
                throw new IllegalArgumentException(
                        "a deterministic machine steps nowhere for no character");
            }
        }
        return new StringPattern(deterministic, accepting, over, target, free, budget);
    }

    /** A set as the ascending {@code from, to} pairs a walk searches. */
    private static int[] pairs(CodePoints over) {
        List<CodePoints.Range> ranges = over.ranges();
        int[] out = new int[ranges.size() * 2];
        for (int at = 0; at < ranges.size(); at++) {
            out[at * 2] = ranges.get(at).from();
            out[at * 2 + 1] = ranges.get(at).to();
        }
        return out;
    }

    /** {@code ranges} as a set an image may write: {@code from, to} pairs of scalar values, each
     *  after the one before it. */
    private static int[] aSet(int[] ranges) {
        int last = -1;
        for (int at = 0; at < ranges.length; at += 2) {
            int from = ranges[at];
            int to = ranges[at + 1];
            if (from <= last || to < from || to > Character.MAX_CODE_POINT
                    || (from <= Character.MAX_SURROGATE && to >= Character.MIN_SURROGATE)) {
                throw new IllegalArgumentException("a set is runs of scalar values in order, which "
                        + from + ".." + to + " after " + last + " is not");
            }
            last = to;
        }
        return ranges;
    }

    /** How many {@link #runs} the steps {@code over} come to, counted without making them. */
    private static long runs(int[][][] over) {
        long out = 0;
        for (int[][] sets : over) {
            for (int[] set : sets) {
                out += set.length / 2;
            }
        }
        return out;
    }

    /** A deterministic machine's steps as sorted runs, so a character is one search. */
    private static int[][] runs(int[][][] over, int[][] target) {
        int[][] out = new int[over.length][];
        for (int state = 0; state < over.length; state++) {
            List<int[]> each = new ArrayList<>();
            for (int step = 0; step < over[state].length; step++) {
                int[] ranges = over[state][step];
                for (int at = 0; at < ranges.length; at += 2) {
                    each.add(new int[] {ranges[at], ranges[at + 1], target[state][step]});
                }
            }
            each.sort((one, other) -> Integer.compare(one[0], other[0]));
            int[] flat = new int[each.size() * 3];
            for (int i = 0; i < each.size(); i++) {
                if (i > 0 && each.get(i)[0] <= each.get(i - 1)[1]) {
                    throw twoWays(state, each.get(i)[0]);
                }
                System.arraycopy(each.get(i), 0, flat, i * 3, 3);
            }
            out[state] = flat;
        }
        return out;
    }

    /** Which states reach one a walk may stop at, walked back from those. */
    private static boolean[] live(boolean[] accepting, int[][] target, int[][] free) {
        int states = accepting.length;
        List<List<Integer>> back = new ArrayList<>(states);
        for (int state = 0; state < states; state++) {
            back.add(new ArrayList<>());
        }
        for (int state = 0; state < states; state++) {
            for (int to : target[state]) {
                back.get(to).add(state);
            }
            for (int to : free[state]) {
                back.get(to).add(state);
            }
        }
        boolean[] out = new boolean[states];
        int[] waiting = new int[states];
        int count = 0;
        for (int state = 0; state < states; state++) {
            if (accepting[state]) {
                out[state] = true;
                waiting[count++] = state;
            }
        }
        while (count > 0) {
            for (int from : back.get(waiting[--count])) {
                if (!out[from]) {
                    out[from] = true;
                    waiting[count++] = from;
                }
            }
        }
        return out;
    }

    /** What a match runs: the {@link #table} where there is one, the runs of a deterministic machine
     *  where not, and otherwise the {@link #subsets} or, where there are none, every state at once. */
    private boolean run(String value, @Nullable Checkpoint checkpoint) {
        if (table != null) {
            return look(value, table, checkpoint);
        }
        if (runs != null) {
            return walk(value, runs, checkpoint);
        }
        if (subsets != null) {
            return remember(value, subsets, checkpoint);
        }
        return spread(value, checkpoint);
    }

    /**
     * One state at a time, over a deterministic machine's {@link #table}: a character is the lookup
     * of its class and the lookup of where that leads.
     */
    private boolean look(String value, Table table, @Nullable Checkpoint checkpoint) {
        SymbolClasses classes = table.classes();
        int[] steps = table.steps();
        int state = 0;
        int at = 0;
        int length = value.length();
        while (at < length) {
            ask(checkpoint);
            char unit = value.charAt(at);
            int each = unit < ASCII ? classes.ascii(unit) : classes.at(value, at);
            if (each < 0) {
                return false;
            }
            int next = steps[state + each];
            at += Character.isHighSurrogate(unit) ? 2 : 1;
            if (next != state) {
                if (next < 0) {
                    return false;
                }
                state = next;
            } else if (checkpoint == null) {
                // A state that steps back to itself is often stayed in for many characters, which
                // are gone over without looking up their classes. A walk that asks before each
                // character goes one at a time.
                at = table.stay(state).over(value, at);
            }
        }
        return accepting[state / classes.count()];
    }

    /** How many characters ASCII is. */
    private static final int ASCII = 128;

    /**
     * One state at a time, over a deterministic machine with no {@link #table}.
     *
     * <p>An ASCII character is one lookup in the {@link Ascii} table where the machine has one, and
     * every other character a search of the state's runs. The table leads nowhere rather than to a
     * state no walk is accepted from, so a walk stops at the same character either way.
     */
    private boolean walk(String value, int[][] runs, @Nullable Checkpoint checkpoint) {
        @Nullable Ascii table = ascii;
        int state = 0;
        int at = 0;
        int length = value.length();
        while (at < length) {
            ask(checkpoint);
            char unit = value.charAt(at);
            if (table != null && unit < ASCII) {
                state = table.steps()[state * table.kinds() + table.kind()[unit]];
                at++;
            } else {
                if (!live[state]) {
                    return false;
                }
                int symbol = value.codePointAt(at);
                at += Character.charCount(symbol);
                state = next(runs[state], symbol);
            }
            if (state < 0) {
                return false;
            }
        }
        return accepting[state];
    }

    /** Where {@code symbol} leads from a state whose runs are {@code runs}, or -1 where it leads
     *  nowhere. */
    private static int next(int[] runs, int symbol) {
        int low = 0;
        int high = runs.length / 3 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (runs[mid * 3 + 1] < symbol) {
                low = mid + 1;
            } else if (runs[mid * 3] > symbol) {
                high = mid - 1;
            } else {
                return runs[mid * 3 + 2];
            }
        }
        return -1;
    }

    /**
     * One set of states at a time, over a machine that is not deterministic, as the set of them
     * ({@link Subset}) it was in before.
     *
     * <p>Where a class leads from a set is worked out the first time a walk asks, as
     * {@link #spread} works out a character, and kept with the set, so a walk that asks again is one
     * lookup. The set it leads to is looked up among the sets kept, so that a walk going round a
     * loop of the pattern goes round a loop of sets and not into new ones. The sets kept are bounded
     * by the {@link Budget}; a walk that needs one more than that goes on from the set it is in as
     * {@link #spread} does.
     *
     * <p>A walk that does not ask goes over a run of characters that keep it in one set as
     * {@link #look} does over a state, by the classes already found to lead from the set back to it
     * ({@link Subset#stay}). The set with no state in it ({@link Subsets#nothing}) is where a walk's answer
     * is known to be no, and no walk goes on from it.
     */
    private boolean remember(String value, Subsets known, @Nullable Checkpoint checkpoint) {
        SymbolClasses classes = known.classes;
        Subset nothing = known.nothing;
        Subset in = known.start;
        if (in == nothing) {
            // No state the walk starts in reaches one it may stop at.
            return false;
        }
        @Nullable Room room = null;
        int at = 0;
        int length = value.length();
        while (at < length) {
            ask(checkpoint);
            char unit = value.charAt(at);
            int each = unit < ASCII ? classes.ascii(unit) : classes.at(value, at);
            if (each < 0) {
                return false;
            }
            Subset next = in.next[each];
            if (next == null) {
                if (room == null) {
                    // The room a walk works a set out in is as large as the machine, and is made
                    // only once asked.
                    ask(checkpoint);
                    room = new Room(accepting.length);
                }
                next = step(known, in, each, room, checkpoint);
                if (next == null) {
                    System.arraycopy(in.states, 0, room.here, 0, in.states.length);
                    return spread(value, at, room, in.states.length, checkpoint);
                }
            }
            at += Character.isHighSurrogate(unit) ? 2 : 1;
            if (next != in) {
                if (next == nothing) {
                    return false;
                }
                in = next;
            } else if (checkpoint == null) {
                // As over a deterministic machine's table ({@link #look}).
                at = in.stay.over(value, at);
            }
        }
        return in.accepting;
    }

    /** Where class {@code each} leads from {@code from}, worked out, kept and answered; or null where
     *  the set it leads to is one more than {@code known} keeps. */
    private @Nullable Subset step(Subsets known, Subset from, int each, Room room,
                                  @Nullable Checkpoint checkpoint) {
        int symbol = known.classes.some(each);
        room.round++;
        room.hash = 0;
        room.accepting = false;
        int count = 0;
        for (int state : from.states) {
            ask(checkpoint);
            int[][] sets = over[state];
            for (int step = 0; step < sets.length; step++) {
                ask(checkpoint);
                if (holds(sets[step], symbol)) {
                    count = close(target[state][step], room.there, count, room, checkpoint);
                }
            }
        }
        Subset to = count == 0 ? known.nothing : known.held(room, count, checkpoint);
        if (to == from) {
            from.staysOn(known.classes, each);
        }
        if (to != null) {
            from.next[each] = to;
        }
        return to;
    }

    /**
     * A set of states a walk over a machine that is not deterministic has been in, and where each
     * class has been found to lead from it.
     *
     * <p>A pattern is asked about from any number of threads at once, and the sets are found by
     * whichever walk gets to one first. Where a class leads ({@link #next}) is written by the walk
     * that found it, without a lock: a walk that reads null works it out again, and one that reads a
     * set reads all of it, since everything a set holds was given to it as it was made.
     */
    private static final class Subset {

        final int[] states;
        final boolean accepting;
        final int hash;
        final @Nullable Subset[] next;

        /**
         * The characters a walk stays here on, made from the classes found to lead back here, one
         * class added each time one is found ({@link #staysOn}).
         *
         * <p>Never worked out by finding where another class leads: which sets are kept is the
         * walks' to decide, and what a walk goes over without looking up is only a quicker way
         * through them. A walk on another thread may find a class in {@link #next} before it finds
         * it here, and goes over that class's characters one at a time until it does.
         */
        volatile SymbolClasses.Stay stay = SymbolClasses.Stay.NONE;

        private static final VarHandle STAY;

        static {
            try {
                STAY = MethodHandles.lookup().findVarHandle(Subset.class, "stay", SymbolClasses.Stay.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        Subset(int[] states, boolean accepting, int hash, int classes) {
            this.states = states;
            this.accepting = accepting;
            this.hash = hash;
            this.next = new Subset[classes];
        }

        /**
         * Adds class {@code each} of {@code classes} to what a walk stays here on, whichever other
         * walks add theirs, or the same, at once ({@link SymbolClasses.Stay#with}). A try is gone
         * round again only where another walk added one first, which each walk does at most once
         * for each class.
         */
        void staysOn(SymbolClasses classes, int each) {
            SymbolClasses.Stay was;
            do {
                was = stay;
            } while (!STAY.compareAndSet(this, was, was.with(classes, each)));
        }
    }

    /** The sets a pattern keeps, each once, and what they may grow to. */
    private static final class Subsets {

        final SymbolClasses classes;
        /**
         * The set with no state in it, which accepts nothing. A step is found to lead to it, and a
         * walk that is led there answers no; no walk is in it, so no step from it is ever found.
         */
        final Subset nothing;
        final Subset start;
        private final Budget budget;
        private final AtomicReferenceArray<Subset> slots;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicLong remembered = new AtomicLong();

        /**
         * The sets a pattern keeps, holding the one of the {@code count} states first in
         * {@code started.there} that a walk starts in; or null where that one is past what they may
         * hold. It is kept as every other set is ({@link #keep}), so no set is held that is not
         * counted.
         */
        static @Nullable Subsets of(SymbolClasses classes, Budget budget, Room started, int count) {
            // No walk is in the set with no state in it, so no step from it is kept.
            Subset nothing = new Subset(new int[0], false, 0, 0);
            if (count == 0) {
                return new Subsets(classes, budget, nothing, nothing);
            }
            Subset start = new Subset(Arrays.copyOf(started.there, count), started.accepting,
                    started.hash, classes.count());
            Subsets out = new Subsets(classes, budget, nothing, start);
            return out.keep(out.slot(start.hash), start) ? out : null;
        }

        private Subsets(SymbolClasses classes, Budget budget, Subset nothing, Subset start) {
            this.classes = classes;
            this.budget = budget;
            this.nothing = nothing;
            this.start = start;
            this.slots = new AtomicReferenceArray<>(Integer.highestOneBit(Math.max(budget.subsets(), 1)) * 4);
        }

        private int slot(int hash) {
            return (hash ^ (hash >>> 16)) & (slots.length() - 1);
        }

        /**
         * The set of the {@code count} states first in {@code room.there}, which are the ones
         * {@code room.seen} marks with its round: the one kept where it is kept, and otherwise kept
         * now. Null where it is one more than is kept.
         */
        @Nullable Subset held(Room room, int count, @Nullable Checkpoint checkpoint) {
            int hash = room.hash;
            int mask = slots.length() - 1;
            @Nullable Subset made = null;
            int at = slot(hash);
            for (int probe = 0; probe <= mask; probe++) {
                ask(checkpoint);
                Subset held = slots.get(at);
                if (held == null) {
                    if (made == null) {
                        made = new Subset(Arrays.copyOf(room.there, count), room.accepting, hash,
                                classes.count());
                    }
                    if (keep(at, made)) {
                        return made;
                    }
                    held = slots.get(at);
                    if (held == null) {
                        // Not taken by another walk, so past what may be kept.
                        return null;
                    }
                }
                if (held.hash == hash && same(held, room, count, checkpoint)) {
                    return held;
                }
                at = (at + 1) & mask;
            }
            return null;
        }

        /** Whether {@code held} is the set {@code room} marks, asking before each state of it. */
        private static boolean same(Subset held, Room room, int count, @Nullable Checkpoint checkpoint) {
            if (held.states.length != count) {
                return false;
            }
            for (int state : held.states) {
                ask(checkpoint);
                if (room.seen[state] != room.round) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Keeps {@code made} at slot {@code at}, counted against the {@link Budget}, and answers
         * whether it is kept: not where it is past what may be kept, nor where another set was put
         * there first. The one place a set is kept, so what is kept and what is counted are one.
         */
        private boolean keep(int at, Subset made) {
            long holds = (long) made.states.length + made.next.length;
            if (count.incrementAndGet() > budget.subsets()) {
                count.decrementAndGet();
                return false;
            }
            if (remembered.addAndGet(holds) > budget.remembered()) {
                count.decrementAndGet();
                remembered.addAndGet(-holds);
                return false;
            }
            if (slots.compareAndSet(at, null, made)) {
                return true;
            }
            count.decrementAndGet();
            remembered.addAndGet(-holds);
            return false;
        }
    }

    /**
     * What a walk over a machine that is not deterministic is held in, as large as the machine: the
     * states it is in and is going into, which of them it has put in this round, and those it has
     * yet to look past for steps for no character. With them, what the states last put in come to.
     */
    private static final class Room {

        int[] here;
        int[] there;
        final int[] seen;
        final int[] pending;
        int round;
        /** The sum of {@link #scatter} over the states put in since it was last set to nought. */
        int hash;
        /** Whether a walk may stop at any of the states put in since this was last set false. */
        boolean accepting;

        Room(int states) {
            this.here = new int[states];
            this.there = new int[states];
            this.seen = new int[states];
            this.pending = new int[states];
        }
    }

    /** A state's part of the hash of a set it is in, which is the same in whatever order the set's
     *  states are put in. */
    private static int scatter(int state) {
        int mixed = state * 0x9E3779B9;
        return mixed ^ (mixed >>> 15);
    }

    /**
     * Every state the walk is in at once, over a machine that is not deterministic.
     *
     * <p>A state is put in the walk once for each character however many ways lead to it, which is
     * what keeps a character's work to the machine's size: {@code seen} holds the character a state
     * was last put in for. Once the subject is read, the states the walk ended in are looked through
     * for one it may stop at, and that is asked about as the rest of the walk is.
     */
    private boolean spread(String value, @Nullable Checkpoint checkpoint) {
        // The room a walk is held in is as large as the machine, and is made only once asked.
        ask(checkpoint);
        Room room = new Room(accepting.length);
        room.round = 1;
        return spread(value, 0, room, close(0, room.here, 0, room, checkpoint), checkpoint);
    }

    /** {@link #spread} from the {@code count} states first in {@code room.here}, with the subject
     *  read up to {@code from}. */
    private boolean spread(String value, int from, Room room, int count, @Nullable Checkpoint checkpoint) {
        int[] here = room.here;
        int[] there = room.there;
        int at = from;
        while (at < value.length()) {
            ask(checkpoint);
            if (count == 0) {
                return false;
            }
            int symbol = value.codePointAt(at);
            at += Character.charCount(symbol);
            room.round++;
            int next = 0;
            for (int i = 0; i < count; i++) {
                ask(checkpoint);
                int state = here[i];
                int[][] sets = over[state];
                for (int step = 0; step < sets.length; step++) {
                    ask(checkpoint);
                    if (holds(sets[step], symbol)) {
                        next = close(target[state][step], there, next, room, checkpoint);
                    }
                }
            }
            int[] was = here;
            here = there;
            there = was;
            count = next;
        }
        for (int i = 0; i < count; i++) {
            ask(checkpoint);
            if (accepting[here[i]]) {
                return true;
            }
        }
        return false;
    }

    /** {@code from} and every state it reaches for no character, put into {@code into} after its
     *  first {@code count}, where {@code room} has not put them in this round; answers how many it
     *  holds now. */
    private int close(int from, int[] into, int count, Room room, @Nullable Checkpoint checkpoint) {
        int[] seen = room.seen;
        int round = room.round;
        if (seen[from] == round || !live[from]) {
            return count;
        }
        int[] pending = room.pending;
        seen[from] = round;
        int top = 0;
        pending[top++] = from;
        int held = count;
        while (top > 0) {
            ask(checkpoint);
            int state = pending[--top];
            into[held++] = state;
            room.hash += scatter(state);
            room.accepting |= accepting[state];
            for (int to : free[state]) {
                ask(checkpoint);
                if (seen[to] != round && live[to]) {
                    seen[to] = round;
                    pending[top++] = to;
                }
            }
        }
        return held;
    }

    /** Whether {@code symbol} is in a set held as ascending {@code from, to} pairs. */
    private static boolean holds(int[] ranges, int symbol) {
        int low = 0;
        int high = ranges.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (ranges[mid * 2 + 1] < symbol) {
                low = mid + 1;
            } else if (ranges[mid * 2] > symbol) {
                high = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * An image being written, by whoever holds a machine.
     *
     * <p>Here beside the reader, so the one format has one owner: a writer elsewhere and a reader
     * here would be two accounts of it, and nothing would hold them to each other.
     *
     * <p>Private to this package. Whether the machine is deterministic is said to the writer rather
     * than worked out by it, so only a writer handed its machine by the code that built it
     * ({@link PatternImages}) knows the answer it gives is true.
     *
     * <p>A set is written once however many steps are over it. The machine a pattern's shape builds
     * writes a repetition out as copies, and each copy steps over the same set, so a class written
     * large is not written again for every copy.
     *
     * <p>Bounded as it is written and not after. Every state, step and set added counts the
     * characters it comes to in the image, and a writer past its limit says so ({@link #holds}) so
     * that whoever is writing stops there, rather than an image being made whole and then found too
     * large. Counted exactly, but for the two counts at the front, which are taken at their widest:
     * a limit counted loosely would refuse machines the image holds. The format's marker is counted
     * with the rest, since it is characters of the image like any other.
     */
    static final class Writer {

        private final boolean deterministic;
        private final long mostCharacters;
        private long characters;
        private final List<String> sets = new ArrayList<>();
        private final Map<String, Integer> known = new HashMap<>();
        private final List<Boolean> accepting = new ArrayList<>();
        private final List<List<int[]>> steps = new ArrayList<>();
        private final List<List<Integer>> free = new ArrayList<>();

        /**
         * @param deterministic  whether the machine is only ever in one state, and so steps nowhere
         *                       for no character
         * @param mostCharacters the most characters the image may take
         */
        public Writer(boolean deterministic, long mostCharacters) {
            this.deterministic = deterministic;
            this.mostCharacters = mostCharacters;
            // The format's marker, the kind, and the two counts written before what they count, at
            // their widest.
            this.characters = WRITTEN_FORMAT.marker().length() + 1 + 2 + 2 * NUMBER;
        }

        /** The most characters one number of an image takes, its comma included. */
        private static final int NUMBER = 11;

        /** The characters {@code value} is written in, its comma included. */
        private static int written(int value) {
            return Integer.toString(value).length() + 1;
        }

        /** The characters the image is counted at so far, which is never fewer than it takes. */
        long counted() {
            return characters;
        }

        /** Whether what has been added so far still fits the image's limit. */
        public boolean holds() {
            return characters <= mostCharacters;
        }

        /**
         * The set {@code ranges} holds, as ascending {@code from, to} pairs, named by the number a
         * step refers to it by.
         */
        public int set(int[] ranges) {
            if (ranges.length % 2 != 0) {
                throw new IllegalArgumentException("a set is written as pairs");
            }
            aSet(ranges);
            StringBuilder out = new StringBuilder();
            out.append(ranges.length / 2);
            for (int each : ranges) {
                out.append(',').append(each);
            }
            String written = out.toString();
            Integer had = known.get(written);
            if (had != null) {
                return had;
            }
            sets.add(written);
            known.put(written, sets.size() - 1);
            characters += written.length() + 1;
            return sets.size() - 1;
        }

        /** One more state, numbered from nought in the order they are asked for; the first is where
         *  a walk begins. */
        public int state(boolean stops) {
            accepting.add(stops);
            steps.add(new ArrayList<>());
            free.add(new ArrayList<>());
            // Whether it stops, and its two counts while they are nought.
            characters += 6;
            return accepting.size() - 1;
        }

        /** A step from {@code from} over the set numbered {@code set}, to {@code to}. */
        public void step(int from, int set, int to) {
            if (set < 0 || set >= sets.size()) {
                throw new IllegalArgumentException("a step is over a set this has been given");
            }
            if (to < 0) {
                throw new IllegalArgumentException("a step leads to a state");
            }
            List<int[]> out = steps.get(from);
            out.add(new int[] {set, to});
            characters += written(set) + written(to) + grown(out.size());
        }

        /** The character a count takes on where it has just grown by a digit. */
        private static int grown(int count) {
            return written(count) - written(count - 1);
        }

        /** A step from {@code from} to {@code to} that takes no character. */
        public void free(int from, int to) {
            if (deterministic) {
                throw new IllegalArgumentException(
                        "a deterministic machine steps nowhere for no character");
            }
            if (to < 0) {
                throw new IllegalArgumentException("a step leads to a state");
            }
            List<Integer> out = free.get(from);
            out.add(to);
            characters += written(to) + grown(out.size());
        }

        /** The image, cut into strings of at most {@link #CHUNK} characters. Asked of a writer that
         *  {@link #holds}. */
        public List<String> image() {
            if (!holds()) {
                throw new IllegalStateException("an image past its limit is not written out");
            }
            // A state may be stepped to before it is made, so where the steps lead is asked once
            // every state is: an image this writes is one {@link StringPattern#of} reads.
            int states = accepting.size();
            if (states == 0) {
                throw new IllegalStateException("a machine has a state to start in");
            }
            for (int state = 0; state < states; state++) {
                for (int[] step : steps.get(state)) {
                    leadsToAState(step[1], states);
                }
                for (int to : free.get(state)) {
                    leadsToAState(to, states);
                }
            }
            StringBuilder out = new StringBuilder();
            out.append(WRITTEN_FORMAT.marker()).append(',');
            out.append(deterministic ? 1 : 0).append(',').append(sets.size());
            for (String each : sets) {
                out.append(',').append(each);
            }
            out.append(',').append(accepting.size());
            for (int state = 0; state < accepting.size(); state++) {
                out.append(',').append(accepting.get(state) ? 1 : 0);
                out.append(',').append(steps.get(state).size());
                for (int[] step : steps.get(state)) {
                    out.append(',').append(step[0]).append(',').append(step[1]);
                }
                out.append(',').append(free.get(state).size());
                for (int to : free.get(state)) {
                    out.append(',').append(to);
                }
            }
            List<String> chunks = new ArrayList<>();
            for (int at = 0; at < out.length(); at += CHUNK) {
                chunks.add(out.substring(at, Math.min(out.length(), at + CHUNK)));
            }
            return List.copyOf(chunks);
        }
    }

    private static void leadsToAState(int to, int states) {
        if (to >= states) {
            throw new IllegalStateException("a step leads to a state the machine has, and there is"
                    + " none numbered " + to + " among " + states);
        }
    }

    /** The numbers of an image, read in order. */
    private static final class Ints {

        private final String text;
        private int at;

        Ints(String text, int at) {
            this.text = text;
            this.at = at;
        }

        int next() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("an image ends before its machine does");
            }
            int value = 0;
            int start = at;
            while (at < text.length() && text.charAt(at) != ',') {
                char digit = text.charAt(at++);
                if (digit < '0' || digit > '9') {
                    throw new IllegalArgumentException("an image is written in numbers: " + digit);
                }
                value = Math.addExact(Math.multiplyExact(value, 10), digit - '0');
            }
            if (at == start) {
                throw new IllegalArgumentException("an image writes no empty number");
            }
            at++;
            return value;
        }

        /** The next number, which says how many of something follow. Each of them takes at least a
         *  character, so a count past what is left of the image is one nothing follows. */
        int count() {
            int value = next();
            if (value > text.length() - Math.min(at, text.length())) {
                throw new IllegalArgumentException("an image ends before its machine does");
            }
            return value;
        }

        /** The next number, which is 1 or 0 for whether {@code what} holds. */
        boolean flag(String what) {
            int value = next();
            if (value > 1) {
                throw new IllegalArgumentException(what + " is written as 0 or 1, and not as " + value);
            }
            return value == 1;
        }

        /** The next number, which names one of {@code many} things numbered from nought. */
        int below(int many, String what) {
            int value = next();
            if (value >= many) {
                throw new IllegalArgumentException(
                        "an image names a " + what + " it has, and there is none numbered " + value);
            }
            return value;
        }

        boolean done() {
            return at >= text.length();
        }
    }

    @Override
    public String toString() {
        return (deterministic ? "a deterministic machine of " : "a machine of ")
                + accepting.length + " states";
    }
}
