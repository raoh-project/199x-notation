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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>A walk reads each character of the subject once, holds no stack and never goes back. A
 * machine is held one of two ways, and each is walked its own way ({@link Way}); none of them
 * decides an answer.
 *
 * <p>A deterministic machine made here or read from an image of P2 is held as its classes and rows
 * ({@link ClassRows}): every character is in one class, and every class leads one way from every
 * state. A character is looked up as its class, and where that leads is one more lookup where the
 * machine's table is within what a pattern may spend ({@link Budget}), and otherwise a search of the
 * state's spans, ASCII being looked up in a table of its own where that is within the budget. Such
 * a machine is never walked as sets of states.
 *
 * <p>Any other machine, the one a pattern's shape builds or one read from an image of P1, is held as
 * its steps: sets of symbols each leading to a state, and steps for no character. The symbols are
 * put into the classes the steps tell apart ({@link SymbolClasses}). Where an image of P1 said the
 * machine is deterministic, which is held to it whatever the budget, it is walked one state at a
 * time over a table or its runs while those are within the budget. Otherwise the walk is in a set
 * of states, and where a class leads from a set is worked out the first time a walk needs it, by
 * moving each state of the set, a state being put in the set at most once; it is kept with the set,
 * so a walk that comes to it again looks it up as over a table. The sets kept are bounded, and a
 * walk that would need one more goes on moving each state it is in for every character.
 *
 * <p>Which machine is run is {@link PatternMachine}'s choice and changes no answer: a pattern whose
 * deterministic machine is too costly to make is run as the machine its shape builds, which has the
 * states {@link PatternStates} counts and no more.
 *
 * <p>A symbol is a scalar value. Text holding half a surrogate pair is no text, and no set or
 * class holds a surrogate, so such text is accepted by nothing.
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
 * asks the work is a search of a state's steps or spans, or of the classes past the Basic
 * Multilingual Plane.
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

    /** Whether a walk is only ever in one state: the machine is one by its {@link #rows}, or was
     *  said to be one by its steps and held to it ({@link #oneWay}). How it is walked is the
     *  {@link Budget}'s, and this is not. */
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
     * states step over is held once in {@link Steps#over} and once a state here. So they are made
     * only where a walk goes this way, and counted before they are.
     */
    private final int @Nullable [][] runs;

    /**
     * A machine as its steps: for each state, the sets its steps are over, as {@code from, to}
     * pairs, where each leads, and the states a walk is also in for no character. What a walk as
     * sets of states searches, and what the other ways of walking such a machine are made from.
     *
     * <p>A machine read from an image of P1 or built from a pattern's shape is held so. A
     * deterministic machine that has its {@link ClassRows} is held as those instead and has no
     * steps: nothing about it is worked out from sets, and it is never walked as sets of states.
     */
    private record Steps(int[][][] over, int[][] target, int[][] free) {}

    /** The machine as its steps, or null where it is held as its {@link #rows}. */
    private final @Nullable Steps steps;

    /** The machine as its classes and rows, or null where it is held as its {@link #steps}. */
    private final @Nullable ClassRows rows;

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
     *                     walked as sets of states, as a machine that is not deterministic is
     * @param subsets      the most sets of states a pattern keeps for its walks at one time, in one
     *                     {@link Generation} ({@link #remember})
     * @param remembered   the most states and steps those sets hold between them, each set's states
     *                     and one step for each class; each is an {@code int} or a reference, about
     *                     four bytes. The set a walk starts in, and the one the walk that makes a
     *                     generation has come to, are kept whatever they take as the generation is
     *                     made, and counted beside both, not in them, so the budget bounds every
     *                     other set ({@link Generation})
     */
    record Budget(long classWork, int tableEntries, int asciiEntries, int runs, int subsets,
                  long remembered) {

        /** The most sets a budget may keep: the places they are looked up in are four times as
         *  many, and are made with each generation of them. */
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
        /** One state at a time, an ASCII character a lookup and any other a lookup of its class and
         *  a search of the state's spans: a machine held as its {@link ClassRows} with no table. */
        ASCII_AND_SPANS,
        /** One state at a time, each character a lookup of its class and a search of the state's
         *  spans for where that leads: a machine held as its {@link ClassRows} with no table. */
        SPANS,
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
     * for a machine that is not deterministic, for one with no {@link #classes}, and for one whose
     * table is past the {@link Budget}.
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
    private record Ascii(byte[] kind, int kinds, int[] steps) {

        /** Where ASCII character {@code unit} leads from {@code state}, or -1 where it leads nowhere
         *  or to a state from which no walk is accepted. */
        int next(int state, char unit) {
            return steps[state * kinds + kind[unit]];
        }
    }

    /** The sets of states a walk has been found to be in, for a machine walked as sets of states:
     *  one that is not deterministic, or one that is and has neither a {@link #table} nor
     *  {@link #runs}. Null for any other, where there are no {@link #classes}, or where the first set
     *  is past the {@link Budget}. */
    private final @Nullable Subsets subsets;

    /** The room the last walk as sets of states to finish left for the next, or null where a walk
     *  has it or none has finished ({@link Room}). */
    private final AtomicReference<@Nullable Room> spare = new AtomicReference<>();

    private StringPattern(boolean deterministic, boolean[] accepting, int[][][] over, int[][] target,
                          int[][] free, Budget budget) {
        this.accepting = accepting;
        Steps steps = new Steps(over, target, free);
        this.steps = steps;
        this.rows = null;
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
                ? subsets(steps, classes, budget) : null;
    }

    /**
     * A deterministic machine held as its {@link ClassRows}, walked over its {@link #table} where
     * that is within {@code budget} and otherwise over its rows' spans.
     *
     * <p>Nothing is asked of it: rows lead each class one way from each state by what they are, so
     * there is no {@link #oneWay} to hold them to, and no class is worked out again from sets. It
     * has no {@link #steps}, and is never walked as sets of states.
     */
    private StringPattern(ClassRows rows, Budget budget) {
        int states = rows.states();
        this.deterministic = true;
        this.accepting = new boolean[states];
        for (int state = 0; state < states; state++) {
            accepting[state] = rows.stopsAt(state);
        }
        this.live = rows.live();
        this.steps = null;
        this.rows = rows;
        SymbolClasses classes = null;
        if (budget.classWork() > 0) {
            classes = SymbolClasses.of(rows.partition(),
                    new Meter(1, 1, budget.classWork()).making());
        }
        this.classes = classes;
        this.table = classes != null && (long) states * rows.classes() <= budget.tableEntries()
                ? table(rows, live, classes) : null;
        this.runs = null;
        this.ascii = table == null ? ascii(rows, live, budget) : null;
        this.subsets = null;
    }

    /** The {@link Ascii} table of a machine held as its rows, or null where it would hold more than
     *  the {@link Budget} allows. The kinds are the classes ASCII characters are in, each asked of
     *  each state's spans once. */
    private static @Nullable Ascii ascii(ClassRows rows, boolean[] live, Budget budget) {
        byte[] kind = new byte[ASCII];
        int[] classOfKind = new int[ASCII];
        int kinds = 0;
        for (int c = 0; c < ASCII; c++) {
            int of = rows.classAt(c);
            int found = 0;
            while (found < kinds && classOfKind[found] != of) {
                found++;
            }
            if (found == kinds) {
                classOfKind[kinds++] = of;
            }
            kind[c] = (byte) found;
        }
        if ((long) rows.states() * kinds > budget.asciiEntries()) {
            return null;
        }
        int[] steps = new int[rows.states() * kinds];
        for (int state = 0; state < rows.states(); state++) {
            for (int each = 0; each < kinds; each++) {
                int to = rows.next(state, classOfKind[each]);
                steps[state * kinds + each] = live[to] ? to : -1;
            }
        }
        return new Ascii(kind, kinds, steps);
    }

    /** The {@link #table} of a machine held as its rows: each span fills the classes it covers in
     *  its state's row. */
    private static Table table(ClassRows rows, boolean[] live, SymbolClasses classes) {
        int width = classes.count();
        int states = rows.states();
        int[] steps = new int[states * width];
        for (int state = 0; state < states; state++) {
            int each = 0;
            for (int span = 0; span < rows.spans(state); span++) {
                int to = rows.to(state, span);
                int written = live[to] ? to * width : -1;
                for (; each <= rows.end(state, span); each++) {
                    steps[state * width + each] = written;
                }
            }
        }
        return new Table(classes, steps, new SymbolClasses.Stay[states]);
    }

    /** How this pattern walks a subject. */
    Way way() {
        if (table != null) {
            return Way.TABLE;
        }
        if (runs != null) {
            return ascii != null ? Way.ASCII_AND_RUNS : Way.RUNS;
        }
        if (rows != null) {
            return ascii != null ? Way.ASCII_AND_SPANS : Way.SPANS;
        }
        return subsets != null ? Way.SETS_KEPT : Way.EVERY_STATE;
    }

    /** How many sets of states this pattern keeps now, none where it keeps none: what a test of which
     *  way a walk went asks, and no walk. */
    int setsKept() {
        Subsets known = subsets;
        if (known == null) {
            return 0;
        }
        Generation now = known.current();
        return now.count.get() + now.needed;
    }

    /** What the generation kept now counts against the budget, and what the sets and table it holds
     *  within the budget take, worked out from them: what a test of the counting asks, and no walk. */
    long[] counted() {
        Subsets known = subsets;
        if (known == null) {
            return new long[] {0, 0};
        }
        Generation now = known.current();
        return new long[] {now.remembered.get(), now.held()};
    }

    /** How many of the sets kept now are kept whatever they take, beside the budget, and the states
     *  and steps they hold: what a test of what a generation holds asks, and no walk. */
    long[] keptBeside() {
        Subsets known = subsets;
        if (known == null) {
            return new long[] {0, 0};
        }
        Generation now = known.current();
        return new long[] {now.needed, now.beside};
    }

    /** How many steps from the sets kept now, each where one class leads from one set, are worked
     *  out. */
    int stepsKnown() {
        Subsets known = subsets;
        return known == null ? 0 : known.current().stepsKnown();
    }

    /** Forgets every step worked out from the sets kept now, and what a walk stays in each on, and
     *  keeps the sets: a walk after works out each step again, and finds the set it leads to among
     *  those kept. What a test or a timing of that way does, and no walk. */
    void forgetSteps() {
        Subsets known = subsets;
        if (known != null) {
            known.current().forgetSteps();
        }
    }

    /** Starts the sets kept again, as a walk on another thread that filled them does: a walk going on
     *  in the ones kept now finds a new generation in their place when it fills them. What a test
     *  does, and no walk. */
    void startSetsAgain() {
        Subsets known = subsets;
        if (known != null) {
            known.restart(known.current(), null, -1, null);
        }
    }

    /** Leaves a room for the next walk whose rounds are at {@code round}, so that the walks after go
     *  past where the rounds come round to nought. What a test does, and no walk. */
    void leaveRoomAt(int round) {
        Room room = room();
        room.round = round;
        spare.set(room);
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

    /** The {@link #subsets} of a machine walked as sets of states, holding the one a walk starts
     *  in: the first state and every live one it reaches for no character, whatever it takes. */
    private Subsets subsets(Steps steps, SymbolClasses classes, Budget budget) {
        Room room = new Room(accepting.length);
        room.nextRound();
        int count = close(steps, 0, room.there, 0, room, null);
        return Subsets.of(classes, budget, room, count, accepting);
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
     * is taken away. Which format is written is the writers' ({@link P1Writer}, {@link #imageOfP2}),
     * apart from this, so that writing a new format does not stop the old ones being read.
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

        /** The kind, the sets, and the states with their steps, as numbers between commas, as
         *  {@code image/P1.md} defines it. */
        P1("P1"),

        /** The pieces of the scalar values with their classes, and the states with their spans of
         *  classes, as numbers between commas, as {@code image/P2.md} defines it. */
        P2("P2");

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
     * <p>Whether a machine an image of P1 says is deterministic leads one way is asked of its sets,
     * whatever a walk over it is given to walk faster on ({@link #oneWay}), and that is not bounded
     * by the image's length. An image of P2 cannot write a machine that leads a symbol two ways, and
     * reading one is as long as the image ({@link #readP2}).
     *
     * @param image the image, as the strings it was cut into
     * @return the pattern it writes
     */
    public static StringPattern of(List<String> image) {
        return of(image, Budget.DEFAULT);
    }

    /** {@link #of(List)}, given {@code budget} to walk faster on, so that each {@link Way} a walk
     *  over a machine read from an image goes can be run. */
    static StringPattern of(List<String> image, Budget budget) {
        String text = String.join("", image);
        int comma = text.indexOf(',');
        ImageFormat format = ImageFormat.named(comma < 0 ? text : text.substring(0, comma));
        Ints in = new Ints(text, comma < 0 ? text.length() : comma + 1);
        return switch (format) {
            case P1 -> readP1(in, budget);
            case P2 -> readP2(in, budget);
        };
    }

    /**
     * The machine an image of {@link ImageFormat#P2} writes, read from after its marker.
     *
     * <p>Each rule {@code image/P2.md} gives is asked of the number being read and of what was read
     * just before it, so what this does is as long as the image. The pieces end at U+10FFFF and a
     * state's spans at the greatest class, and neither is counted.
     */
    private static StringPattern readP2(Ints in, Budget budget) {
        IntList lasts = new IntList();
        IntList classOf = new IntList();
        int greatest = -1;
        int last = -1;
        while (last != Character.MAX_CODE_POINT) {
            int ends = in.next();
            if (ends <= last || ends > Character.MAX_CODE_POINT
                    || (ends >= Character.MIN_SURROGATE && ends <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("a piece ends at a scalar value after the one"
                        + " before it ends, which " + ends + " after " + last + " is not");
            }
            int of = in.next();
            if (of > greatest + 1) {
                throw new IllegalArgumentException("a piece is in a class an earlier one is in or in"
                        + " the next, and not in class " + of + " after class " + greatest);
            }
            if (classOf.size() > 0 && classOf.last() == of) {
                throw new IllegalArgumentException("two pieces next to each other are in two classes,"
                        + " and these are both in class " + of);
            }
            greatest = Math.max(greatest, of);
            lasts.add(ends);
            classOf.add(of);
            last = ends;
        }
        int states = in.count();
        if (states == 0) {
            throw new IllegalArgumentException("a machine has a state to start in");
        }
        boolean[] accepting = new boolean[states];
        int[][] ends = new int[states][];
        int[][] to = new int[states][];
        IntList spanEnds = new IntList();
        IntList spanTo = new IntList();
        for (int state = 0; state < states; state++) {
            accepting[state] = in.flag("whether a walk stops at a state");
            spanEnds.clear();
            spanTo.clear();
            int end = -1;
            while (end != greatest) {
                int next = in.next();
                if (next <= end || next > greatest) {
                    throw new IllegalArgumentException("a span ends at a class after the one before"
                            + " it ends and no later than the greatest, " + greatest + ", which "
                            + next + " after " + end + " is not");
                }
                int leads = in.below(states, "state");
                if (spanTo.size() > 0 && spanTo.last() == leads) {
                    throw new IllegalArgumentException("two spans next to each other lead to two"
                            + " states, and these both lead to state " + leads);
                }
                spanEnds.add(next);
                spanTo.add(leads);
                end = next;
            }
            ends[state] = spanEnds.toArray();
            to[state] = spanTo.toArray();
        }
        if (!in.done()) {
            throw new IllegalArgumentException("an image holds one machine and nothing after it");
        }
        return new StringPattern(new ClassRows(lasts.toArray(), classOf.toArray(), greatest + 1,
                accepting, ends, to), budget);
    }

    /**
     * What {@code rows} accepts, run as they are: a deterministic machine held as its classes and
     * rows, whether it was read from an image of P2 or made here ({@link ClassRows#of(Automaton)}).
     *
     * @param rows the machine's rows
     * @return the pattern
     */
    static StringPattern of(ClassRows rows) {
        return of(rows, Budget.DEFAULT);
    }

    /** {@link #of(ClassRows)}, given {@code budget} to walk faster on, so that each {@link Way} a
     *  walk over rows goes can be run. */
    static StringPattern of(ClassRows rows, Budget budget) {
        return new StringPattern(rows, budget);
    }

    /** Numbers read one at a time, as many as an image writes: a growing array, so that nothing is
     *  made before the image has said it. */
    private static final class IntList {

        private int[] values = new int[8];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        int last() {
            return values[size - 1];
        }

        void clear() {
            size = 0;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    /** The machine an image of {@link ImageFormat#P1} writes, read from after its marker. */
    private static StringPattern readP1(Ints in, Budget budget) {
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
        return new StringPattern(deterministic, accepting, over, target, free, budget);
    }

    /**
     * What {@code machine} accepts, held as its steps and run as it is, without being written into
     * an image and read back.
     *
     * <p>For the machine a pattern's shape builds, which a caller that runs a pattern where it reads
     * it has nowhere to carry an image for. The machine is one this package built, so what an image
     * is checked for on the way in holds of it already, and a set a machine steps over twice is held
     * once. It is walked as sets of states whatever it is: a machine made deterministic is run as
     * its rows ({@link #of(ClassRows)}), and the only machine held as steps that is walked one state
     * at a time is one an image of P1 said is deterministic ({@link #of(List)}).
     *
     * @param machine the machine
     */
    static StringPattern of(Automaton machine) {
        return of(machine, Budget.DEFAULT);
    }

    /** {@link #of(Automaton)}, given {@code budget} to walk faster on, so that each {@link Way} a
     *  walk goes can be run where the one it would be given is another. */
    static StringPattern of(Automaton machine, Budget budget) {
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
        }
        return new StringPattern(false, accepting, over, target, free, budget);
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
        ClassRows byRows = rows;
        if (byRows != null) {
            return trace(value, byRows, checkpoint);
        }
        Steps bySteps = steps;
        if (bySteps == null) {
            throw new IllegalStateException("a machine is held as its rows or as its steps");
        }
        if (runs != null) {
            return walk(value, runs, checkpoint);
        }
        if (subsets != null) {
            return remember(bySteps, value, subsets, checkpoint);
        }
        return spread(bySteps, value, checkpoint);
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
            int each = classes.at(value, at);
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
                state = table.next(state, unit);
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

    /**
     * One state at a time, over a machine held as its rows with no {@link #table}: an ASCII
     * character is one lookup in the {@link Ascii} table where the machine has one, and any other a
     * lookup of its class, in the {@link #classes} where there are some and otherwise in the rows,
     * and then a search of the state's spans for where that leads. The table leads nowhere rather
     * than to a state no walk is accepted from, so a walk stops at the same character either way.
     * Half a surrogate pair is in no class, and is accepted by nothing.
     */
    private boolean trace(String value, ClassRows rows, @Nullable Checkpoint checkpoint) {
        @Nullable SymbolClasses known = classes;
        @Nullable Ascii table = ascii;
        int state = 0;
        int at = 0;
        int length = value.length();
        while (at < length) {
            ask(checkpoint);
            if (state < 0 || !live[state]) {
                return false;
            }
            char unit = value.charAt(at);
            if (table != null && unit < ASCII) {
                state = table.next(state, unit);
                at++;
                continue;
            }
            int each;
            if (known != null) {
                each = known.at(value, at);
                if (each < 0) {
                    return false;
                }
                at += Character.isHighSurrogate(unit) ? 2 : 1;
            } else {
                int symbol = value.codePointAt(at);
                if (symbol >= Character.MIN_SURROGATE && symbol <= Character.MAX_SURROGATE) {
                    return false;
                }
                at += Character.charCount(symbol);
                each = rows.classAt(symbol);
            }
            state = rows.next(state, each);
        }
        return state >= 0 && accepting[state];
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
     * One set of states at a time, over a machine walked as sets of states, as the set of them
     * ({@link Subset}) it was in before.
     *
     * <p>Where a class leads from a set is worked out the first time a walk asks, as
     * {@link #spread} works out a character, and kept with the set, so a walk that asks again is one
     * lookup. The set it leads to is looked up among the sets kept, so that a walk going round a
     * loop of the pattern goes round a loop of sets and not into new ones.
     *
     * <p>The sets kept are a {@link Generation}, bounded by the {@link Budget}. A walk that needs one
     * more than its generation holds goes into another, and what it may do then is decided in one
     * place ({@link Keeping#into}), which counts every generation a walk goes into, whichever walk
     * started it. It goes into one the first time it fills its own, whatever came before it, and
     * again only where it has read ten characters by steps already worked out for each it worked out
     * a state at a time since it last went into one. Where it makes the generation it goes into, the set it has come to
     * is kept there whatever it takes, beside the set a walk starts in, as the generation is made;
     * where another walk made it, the set is kept there within the budget, or the walk keeps no
     * more. Otherwise the sets it comes to are too seldom met again to be worth
     * keeping, and it keeps no more for the rest of this match: it moves each state for every
     * character, as {@link #spread} does, and looks the set it comes to up among those kept, going
     * on by their steps from one it finds and writing none. So what a match pays for keeping sets is
     * at most two generations of them beyond what it reads by their steps, however many generations
     * other walks start. What a walk decides here is its own and goes no further than the match: the
     * next match starts as the first did, and only the sets and their steps are held past it.
     *
     * <p>A walk that does not ask goes over a run of characters that keep it in one set as
     * {@link #look} does over a state, by the classes already found to lead from the set back to it
     * ({@link Subset#stay}). The set with no state in it ({@link Subsets#nothing}) is where a walk's answer
     * is known to be no, and no walk goes on from it.
     */
    private boolean remember(Steps steps, String value, Subsets known, @Nullable Checkpoint checkpoint) {
        SymbolClasses classes = known.classes;
        int width = known.width;
        Subset nothing = known.nothing;
        Generation kept = known.current();
        // The set the walk is in, which is one of `kept`; or null where the walk is moving each
        // state, and is in the first `count` of room.here.
        @Nullable Subset in = kept.start;
        if (in == nothing) {
            // No state the walk starts in reaches one it may stop at.
            return false;
        }
        @Nullable Room room = null;
        int count = 0;
        // What this match has decided, and nothing past it.
        Keeping keeping = new Keeping();
        int at = 0;
        int length = value.length();
        try {
            while (at < length) {
                ask(checkpoint);
                char unit = value.charAt(at);
                int each = classes.at(value, at);
                if (each < 0) {
                    return false;
                }
                at += Character.isHighSurrogate(unit) ? 2 : 1;
                Subset from = in;
                if (from != null) {
                    // A class ASCII is in is one lookup in the set's row; another is looked for in
                    // the generation's table of them.
                    @Nullable Subset next = each < width ? from.next[each] : kept.cold(from, each, checkpoint);
                    if (next != null) {
                        keeping.read++;
                        if (next == nothing) {
                            return false;
                        }
                        if (next != from) {
                            in = next;
                        } else if (checkpoint == null) {
                            // As over a deterministic machine's table ({@link #look}).
                            int was = at;
                            at = from.stay.over(value, at);
                            keeping.read += at - was;
                        }
                        continue;
                    }
                    if (room == null) {
                        // The room a walk works a set out in is as large as the machine, and is
                        // taken only once asked.
                        ask(checkpoint);
                        room = room();
                    }
                    count = move(steps, from.states, from.states.length, classes.some(each), room,
                            checkpoint);
                    if (!keeping.frozen) {
                        keeping.worked++;
                    }
                } else {
                    count = move(steps, Objects.requireNonNull(room).here, count, classes.some(each),
                            room, checkpoint);
                }
                Room working = Objects.requireNonNull(room);
                // The set the walk has come to, kept where it is kept or there is room for it, and
                // the step to it, written only from a set of the same generation and not by a walk
                // that keeps no more. The set with no state in it is in no generation.
                @Nullable Subset to = count == 0 ? nothing
                        : kept.held(working, count, accepting, !keeping.frozen, checkpoint);
                boolean fits = to != null
                        && (from == null || keeping.frozen || kept.learn(from, each, to, checkpoint));
                if (!fits && !keeping.frozen) {
                    // No room for the set or for the step to it: what is done is decided in one
                    // place, as for a set and a step alike.
                    int over = from != null && from == kept.start ? each : -1;
                    @Nullable Generation into = keeping.into(known, kept, working, count, accepting,
                            over, checkpoint);
                    if (into != null) {
                        kept = into;
                        // The walk that made the generation finds the set it has come to there,
                        // with the step to it from the set a walk starts in where it came from that
                        // one; one that went into a generation another walk made keeps the set
                        // within the budget, or keeps no more, and a later walk works out the step.
                        if (count == 0) {
                            to = nothing;
                        } else if (into.entry != null && into.entry == keeping.entered) {
                            to = into.entry;
                        } else {
                            to = kept.held(working, count, accepting, true, checkpoint);
                            if (to == null) {
                                keeping.frozen = true;
                            }
                        }
                    }
                    // Where it keeps no more, the walk goes on from the set it came to where that
                    // is kept, with no step to it.
                }
                if (count == 0) {
                    return false;
                }
                if (to == null) {
                    // Not kept, and no more will be: the walk goes on from the states it has come to.
                    int[] was = working.here;
                    working.here = working.there;
                    working.there = was;
                    in = null;
                    continue;
                }
                if (to == from && checkpoint == null) {
                    int was = at;
                    at = to.stay.over(value, at);
                    keeping.read += at - was;
                }
                in = to;
            }
            if (in != null) {
                return in.accepting;
            }
            return acceptsAny(accepting, Objects.requireNonNull(room).here, count, checkpoint);
        } finally {
            if (room != null) {
                leave(room);
            }
        }
    }

    /**
     * What one match decides about keeping the sets it comes to, and nothing past the match: the
     * characters it worked out a state at a time and those it read by steps already worked out since
     * it last went into a generation or since it began, whether it has gone into one, and whether
     * it keeps no more.
     *
     * <p>{@link #into} is the one place a walk goes from the generation it keeps sets in to another,
     * so every such change is counted against the match: one it started, and one another walk
     * started that it takes up. Were a generation another walk started taken up without being
     * counted, a walk among others that keep starting them would keep sets in each, and what one
     * match pays for keeping sets would have no bound.
     */
    private static final class Keeping {

        /** The characters this match worked out a state at a time to keep, whether that came to a
         *  new set or to a kept one by a new step, and those it read by steps already worked out,
         *  since it last went into a generation or since it began. */
        long worked;
        long read;
        private boolean changed;
        boolean frozen;
        /** The set this walk made a generation with, kept there whatever it took, or null. */
        @Nullable Subset entered;

        /**
         * The generation the walk is to keep sets in, now that {@code full} has no room for the set
         * it has come to, the {@code count} states first in {@code room.there}, or for the step to
         * it: the one kept now, where another walk has started it, and otherwise one started here in
         * its place, holding that set beside the set a walk starts in ({@link #entered}), and the
         * step over class {@code over} to it from the set a walk starts in, where the walk came from
         * that one, and {@code over} is not -1. The first time in a match the walk goes into one
         * whatever it read; after that only where it read ten characters by steps already worked
         * out for each it worked out a state at a time since. Counted in sets made, a walk whose new
         * steps led only to kept sets, and filled the budget with steps, counted nothing it had
         * worked out, and went into a new generation each time it filled one. Null where it does
         * not, and it keeps no more sets for the rest of the match.
         */
        @Nullable Generation into(Subsets known, Generation full, Room room, int count,
                                  boolean[] accepting, int over, @Nullable Checkpoint checkpoint) {
            if (changed && read < 10 * worked) {
                frozen = true;
                return null;
            }
            changed = true;
            worked = 0;
            read = 0;
            entered = null;
            Generation now = known.current();
            if (now != full) {
                return now;
            }
            ask(checkpoint);
            @Nullable Subset entry = null;
            @Nullable Subset overTo = null;
            if (count == 0) {
                // The set with no state in it is in no generation.
                overTo = known.nothing;
            } else if (!Generation.same(full.start, room, count, checkpoint)) {
                entry = new Subset(Arrays.copyOf(room.there, count),
                        acceptsAny(accepting, room.there, count, checkpoint),
                        hashOf(room.there, count, checkpoint), known.width, Generation.ENTRY);
                overTo = entry;
            }
            now = known.restart(full, entry, over, overTo);
            if (entry != null && now.entry == entry) {
                entered = entry;
            }
            return now;
        }
    }

    /** The states the first {@code count} of {@code from} lead to over {@code symbol}, each with the
     *  live states it reaches for no character, put first in {@code room.there} in a round of their
     *  own; answers how many. The one place a walk as sets of states moves its states, asking
     *  before each state and each step it looks at. */
    private int move(Steps steps, int[] from, int count, int symbol, Room room,
                     @Nullable Checkpoint checkpoint) {
        room.nextRound();
        int next = 0;
        for (int i = 0; i < count; i++) {
            ask(checkpoint);
            int state = from[i];
            int[][] sets = steps.over()[state];
            for (int step = 0; step < sets.length; step++) {
                ask(checkpoint);
                if (holds(sets[step], symbol)) {
                    next = close(steps, steps.target()[state][step], room.there, next, room,
                            checkpoint);
                }
            }
        }
        return next;
    }

    /**
     * A set of states a walk as sets of states has been in, and where each
     * class has been found to lead from it.
     *
     * <p>A pattern is asked about from any number of threads at once, and the sets are found by
     * whichever walk gets to one first. Where a class leads ({@link #next}) is written by the walk
     * that found it, without a lock: a walk that reads null works it out again, and one that reads a
     * set reads all of it, since everything a set holds was given to it as it was made. It leads
     * only to a set of its own {@link Generation}, or to the set with no state in it.
     *
     * <p>{@link #next} has a place for every class of the machine where the budget's share for one
     * set holds them, and otherwise for each class ASCII is in, at most 128 ({@link Subsets#width}),
     * and is counted in the budget at that length. Where the other classes lead is kept in the
     * generation's table of them ({@link Cold}), as it is found, so what a set holds does not grow
     * with how many classes the machine has.
     */
    private static final class Subset {

        final int[] states;
        final boolean accepting;
        final int hash;
        /** What names the set in its generation's table of the steps past its row
         *  ({@link Generation#cold}): unique in the generation, and never nought for a kept set. */
        final int id;
        /** Where each class ASCII is in leads, those being the first classes
         *  ({@link SymbolClasses#asciiWidth}), or null where that is not known yet. Where the other
         *  classes lead is kept in the generation ({@link Generation#cold}), as they are found. */
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

        Subset(int[] states, boolean accepting, int hash, int width, int id) {
            this.states = states;
            this.accepting = accepting;
            this.hash = hash;
            this.id = id;
            this.next = new Subset[width];
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

    /**
     * The sets a pattern keeps for its walks: the set with no state in it and the states a walk
     * starts in, which are the pattern's own, and the {@link Generation} of sets kept now, which a
     * walk may let go and start again ({@link #restart}).
     */
    private static final class Subsets {

        final SymbolClasses classes;
        /**
         * The set with no state in it, which accepts nothing. A step is found to lead to it, and a
         * walk that is led there answers no; no walk is in it, so no step from it is ever found.
         * It is in no generation, and every generation's steps may lead to it.
         */
        final Subset nothing;
        /** The states a walk starts in, every one reached for no character taken; and whether a walk
         *  may stop at one of them, and their hash, which every generation's first set is made of. */
        private final int[] start;
        private final boolean startAccepting;
        private final int startHash;
        private final Budget budget;
        /**
         * How many classes each set's row holds: every class of the machine where they are at most
         * the budget's share for one set, what it holds over the sets it keeps, so that a step over
         * any character is one lookup; and otherwise those ASCII is in, which come first, so that
         * what a set holds is bounded by the budget and not by the machine.
         */
        final int width;
        private final AtomicReference<Generation> current;

        /**
         * The sets a pattern keeps, holding the one of the {@code count} states first in
         * {@code started.there} that a walk starts in, whatever it takes, as every generation is
         * made ({@link Generation#Generation}), and counted beside the budget. {@code accepting}
         * is the states a walk may stop at.
         */
        static Subsets of(SymbolClasses classes, Budget budget, Room started, int count,
                          boolean[] accepting) {
            // No walk is in the set with no state in it, so no step from it is kept.
            Subset nothing = new Subset(new int[0], false, 0, 0, 0);
            Subsets out = new Subsets(classes, budget, nothing, Arrays.copyOf(started.there, count),
                    acceptsAny(accepting, started.there, count, null),
                    hashOf(started.there, count, null));
            out.current.set(out.generation(null, -1, null));
            return out;
        }

        private Subsets(SymbolClasses classes, Budget budget, Subset nothing, int[] start,
                        boolean startAccepting, int startHash) {
            this.classes = classes;
            this.budget = budget;
            this.nothing = nothing;
            this.start = start;
            this.startAccepting = startAccepting;
            this.startHash = startHash;
            long share = budget.remembered() / Math.max(1, budget.subsets());
            this.width = classes.count() <= share ? classes.count() : classes.asciiWidth();
            this.current = new AtomicReference<>();
        }

        /** The generation of sets kept now. */
        Generation current() {
            return current.get();
        }

        /**
         * A new generation in place of {@code full}, holding the set a walk starts in and
         * {@code entry}, the set the walk making it has come to, where that is not null, and the step
         * over class {@code over} from the first to {@code overTo}, or to itself where that is null,
         * where {@code over} is not -1; or, where another walk has put one in its place first, that
         * one, which holds nothing of them. The full one is let go: a walk in it goes on in it until
         * it ends, and it is gone once none is.
         */
        Generation restart(Generation full, @Nullable Subset entry, int over, @Nullable Subset overTo) {
            Generation fresh = generation(entry, over, overTo);
            return current.compareAndSet(full, fresh) ? fresh : current.get();
        }

        /**
         * A generation holding a set of the states a walk starts in, or the set with no state in it
         * where there are none, and {@code entry} where it is not null, each kept whatever it takes,
         * with the step {@link #restart} says. Every match needs the first, so no budget leaves a
         * pattern walking every state for want of room for it.
         */
        private Generation generation(@Nullable Subset entry, int over, @Nullable Subset overTo) {
            if (start.length == 0) {
                return new Generation(classes, budget, width, nothing, null, -1, null);
            }
            return new Generation(classes, budget, width,
                    new Subset(start, startAccepting, startHash, width, Generation.START), entry, over,
                    overTo);
        }
    }

    /**
     * The sets of states kept at one time, each once, where they are looked up, and what they may
     * grow to.
     *
     * <p>A step from a set of one generation leads only to a set of the same one, or to the set with
     * no state in it, which is in none: a generation let go holds nothing of the one after it, and
     * is gone once no walk is in it. So what a pattern holds at once is the generation kept now and
     * one for each walk still going on in an older one.
     *
     * <p>What one generation holds is its slots, made with it, four for each set the budget keeps,
     * and the sets kept in it, each counted as it is kept, as many states and steps as its arrays
     * hold. Two sets are kept whatever they take, and only as the generation is made, before any
     * walk sees it: the set a walk starts in, and the set the walk that made the generation had come
     * to ({@link #entry}). Every walk needs the first, and the walk that filled the generation before
     * this one needs the second to go on by kept steps, so neither is turned away for want of room;
     * they are counted apart, in {@link #needed} and {@link #beside}, which are fixed when the
     * generation is made, and not against the budget, which bounds every other set, in
     * {@link #count} and {@link #remembered}. Every set kept after the generation is made is kept
     * within the budget ({@link #keep}), whichever walk keeps it: a walk that goes into a generation
     * another walk made keeps the set it has come to there only where the budget has room for it.
     * So one generation holds at most the budget and those two sets, however many walks go into it.
     *
     * <p>What is counted here is held across matches, and a walk told to stop leaves wherever it
     * asks. So nothing is counted while anything that asks is still to be done: a walk does what
     * asks first, and then counts, and keeps or gives back what it counted, asking nothing between.
     * Counted in the budget, a set larger than it would leave no room for any other, and a walk that
     * went on from it to one more would be frozen.
     */
    private static final class Generation {

        private final SymbolClasses classes;
        /** The set a walk starts in, kept here as the others are. */
        final Subset start;
        private final Budget budget;
        private final AtomicReferenceArray<Subset> slots;
        final AtomicInteger count = new AtomicInteger();
        final AtomicLong remembered = new AtomicLong();
        /** The ids of the set a walk starts in and of {@link #entry}; the sets kept after are named
         *  from {@link #ids} on. */
        static final int START = 1;
        static final int ENTRY = 2;

        /** The set the walk that made this generation had come to, kept whatever it takes, or null
         *  where it was the set a walk starts in or no walk made it so. */
        final @Nullable Subset entry;
        /** How many classes each set's row holds ({@link Subset#next}). */
        private final int width;
        /** The id the last set kept here was given. */
        private final AtomicInteger ids = new AtomicInteger(ENTRY);
        /** Where the classes past the rows lead from the sets kept here, or null before one is
         *  found ({@link Cold}): put in place by a compare-and-set, and read plainly, so a walk that
         *  reads an older table finds fewer steps and works the rest out again. */
        private @Nullable Cold cold;

        private static final VarHandle COLD;

        static {
            try {
                COLD = MethodHandles.lookup().findVarHandle(Generation.class, "cold", Cold.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        /** The sets kept whatever they take, at most {@link #start} and {@link #entry}, and the
         *  states and steps they hold, beside the budget: fixed as the generation is made. */
        final int needed;
        private final long beside;
        /** What the table of steps past the rows the generation was made with was counted beside the
         *  budget, or nought: a larger one in its place is counted in the budget for what it adds. */
        private final long coldBeside;

        /**
         * A generation holding {@code start}, unless it is the set with no state in it, and
         * {@code entry} where it is not null, each kept whatever it takes, and the step over class
         * {@code over} from {@code start} to {@code overTo}, or to {@code start} where that is null,
         * where {@code over} is not -1. No walk sees it yet, so they are put in it without asking
         * another walk.
         */
        Generation(SymbolClasses classes, Budget budget, int width, Subset start,
                   @Nullable Subset entry, int over, @Nullable Subset overTo) {
            this.classes = classes;
            this.budget = budget;
            this.width = width;
            this.start = start;
            this.entry = entry;
            this.slots = new AtomicReferenceArray<>(Integer.highestOneBit(Math.max(budget.subsets(), 1)) * 4);
            int kept = 0;
            long holds = 0;
            if (start.states.length > 0) {
                slots.set(slot(start.hash), start);
                kept++;
                holds += (long) start.states.length + start.next.length;
            }
            if (entry != null) {
                // At most one other slot is taken, so the probe ends within two.
                int at = slot(entry.hash);
                while (slots.get(at) != null) {
                    at = (at + 1) & (slots.length() - 1);
                }
                slots.set(at, entry);
                kept++;
                holds += (long) entry.states.length + entry.next.length;
            }
            long firstCold = 0;
            if (over >= 0) {
                Subset to = overTo == null ? start : overTo;
                if (over < width) {
                    start.next[over] = to;
                } else {
                    Cold first = new Cold(Cold.FEWEST);
                    first.put(key(start, over), to, null);
                    cold = first;
                    firstCold = first.holds();
                    holds += firstCold;
                }
                if (to == start) {
                    start.staysOn(classes, over);
                }
            }
            this.needed = kept;
            this.beside = holds;
            this.coldBeside = firstCold;
        }

        /** The key a step over class {@code each} from {@code from} is kept by in {@link Cold},
         *  never nought, since no kept set's id is. */
        private static long key(Subset from, int each) {
            return ((long) from.id << 32) | each;
        }

        /** Where class {@code each}, past the rows, leads from {@code from}, kept here, or null where
         *  that is not known. */
        @Nullable Subset cold(Subset from, int each, @Nullable Checkpoint checkpoint) {
            @Nullable Cold table = cold;
            return table == null ? null : table.get(key(from, each), checkpoint);
        }

        /**
         * Keeps that class {@code each} of {@code classes} leads from {@code from}, kept here, to
         * {@code to}, kept here or the set with no state in it, and answers whether it is kept. A
         * class in the row is kept in it, whose room was counted when the set was kept. Another is
         * kept in the table of them ({@link Cold}), and where the table is half full, in a table
         * twice as large in its place, counted against the budget as it is made: where the budget
         * has no room for it, nothing is kept, and the walk is told, to decide what to do. A step
         * kept in a table as another walk puts a larger one in its place may be lost with the
         * table; it is worked out again where a walk next asks, and no answer turns on it.
         */
        boolean learn(Subset from, int each, Subset to, @Nullable Checkpoint checkpoint) {
            if (each < width) {
                if (to == from) {
                    from.staysOn(classes, each);
                }
                from.next[each] = to;
                return true;
            }
            long key = key(from, each);
            while (true) {
                @Nullable Cold table = (Cold) COLD.getAcquire(this);
                if (table != null && table.put(key, to, checkpoint)) {
                    if (to == from) {
                        from.staysOn(classes, each);
                    }
                    return true;
                }
                int capacity = table == null ? Cold.FEWEST : 2 * table.capacity();
                long more = Cold.holds(capacity) - (table == null ? 0 : table.holds());
                if (remembered.get() + more > budget.remembered()) {
                    return false;
                }
                // A table is as large as the steps kept, and is made only once asked. Making it and
                // filling it ask, and a walk told to stop leaves here: so it is made before anything
                // is counted, and is let go uncounted where the walk stops.
                ask(checkpoint);
                Cold larger = new Cold(capacity);
                if (table != null) {
                    table.copyInto(larger, checkpoint);
                }
                // From here nothing asks until what is counted is settled: kept, or given back.
                if (remembered.addAndGet(more) > budget.remembered()) {
                    remembered.addAndGet(-more);
                    return false;
                }
                if (!COLD.compareAndSet(this, table, larger)) {
                    // Another walk put one in its place first: this one is let go, and counted no more.
                    remembered.addAndGet(-more);
                }
            }
        }

        private int slot(int hash) {
            return (hash ^ (hash >>> 16)) & (slots.length() - 1);
        }

        /**
         * The set of the {@code count} states first in {@code room.there}, which are the ones
         * {@code room.seen} marks with its round: the one kept where it is kept, and otherwise, where
         * {@code admit}, kept now within the budget. Null where it is not kept and is not: where not
         * {@code admit}, where the budget has no room for it, or where every slot is taken. Its hash
         * is summed here to look it up, and whether it accepts, of the states a walk may stop at
         * ({@code accepting}), is asked only where it is kept.
         */
        @Nullable Subset held(Room room, int count, boolean[] accepting, boolean admit,
                              @Nullable Checkpoint checkpoint) {
            int hash = hashOf(room.there, count, checkpoint);
            int mask = slots.length() - 1;
            @Nullable Subset made = null;
            int at = slot(hash);
            for (int probe = 0; probe <= mask; probe++) {
                ask(checkpoint);
                Subset held = slots.get(at);
                if (held == null) {
                    if (!admit) {
                        return null;
                    }
                    if (made == null) {
                        made = new Subset(Arrays.copyOf(room.there, count),
                                acceptsAny(accepting, room.there, count, checkpoint), hash, width,
                                ids.incrementAndGet());
                    }
                    if (keep(at, made)) {
                        return made;
                    }
                    held = slots.get(at);
                    if (held == null) {
                        // Not taken by another walk, so past what the budget holds.
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

        /** {@link StringPattern#counted}: the sets kept within the budget, and the table of steps past
         *  the rows, less what its first table was counted beside the budget. */
        long held() {
            long held = 0;
            for (int at = 0; at < slots.length(); at++) {
                Subset kept = slots.get(at);
                if (kept != null && kept != start && kept != entry) {
                    held += (long) kept.states.length + kept.next.length;
                }
            }
            @Nullable Cold table = (Cold) COLD.getAcquire(this);
            return table == null ? held : held + table.holds() - coldBeside;
        }

        /** {@link StringPattern#stepsKnown}. */
        int stepsKnown() {
            int known = 0;
            for (int at = 0; at < slots.length(); at++) {
                Subset held = slots.get(at);
                if (held != null) {
                    for (Subset to : held.next) {
                        if (to != null) {
                            known++;
                        }
                    }
                }
            }
            @Nullable Cold table = (Cold) COLD.getAcquire(this);
            return table == null ? known : known + table.known();
        }

        /** {@link StringPattern#forgetSteps}. The table of steps past the rows is let go, and what
         *  it was counted for is not given back: a test does this, and no walk. */
        void forgetSteps() {
            for (int at = 0; at < slots.length(); at++) {
                Subset held = slots.get(at);
                if (held != null) {
                    Arrays.fill(held.next, null);
                    held.stay = SymbolClasses.Stay.NONE;
                }
            }
            COLD.setRelease(this, null);
        }

        /** Whether {@code held} is the set {@code room} marks, asking before each state of it. */
        static boolean same(Subset held, Room room, int count, @Nullable Checkpoint checkpoint) {
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
         * Keeps {@code made} at slot {@code at} and answers whether it is kept: counted against the
         * {@link Budget}, and not kept where the budget has no room for it, nor where another set was
         * put there first. The one place a set is kept once the generation is made, and it keeps
         * nothing past the budget: only making a generation does ({@link Generation#Generation}).
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
     * Where the classes past the rows lead from the sets of one {@link Generation}: one table for
     * the generation, looked up by a set's id and the class, so what it holds is the steps worked
     * out and no more, however many classes the machine has. A place holds a key, the id above the
     * class, which is never nought, and the set the step leads to; a step is looked for from the
     * place its key's hash names to the first empty one.
     *
     * <p>Many walks read and write it at once, without a lock: a key is put in an empty place by a
     * compare-and-set, and where it leads after. A walk that finds the key before where it leads
     * reads null, and works the step out again. A table is never more than half full: past that a
     * walk puts one twice as large in its place ({@link Generation#learn}).
     */
    private static final class Cold {

        /** The fewest places a table is made with. */
        static final int FEWEST = 16;

        private static final VarHandle KEYS = MethodHandles.arrayElementVarHandle(long[].class);
        private static final VarHandle TO = MethodHandles.arrayElementVarHandle(Subset[].class);

        /**
         * The keys and where each leads. A walk writes a key by a compare-and-set and where it leads
         * after, with release; it reads both plainly. A walk that reads a key or a target late, or
         * not at all, finds the step not known and works it out again; one that reads a target reads
         * all of it, since everything a set holds was given to it as it was made.
         */
        private final long[] keys;
        private final @Nullable Subset[] to;
        private final AtomicInteger count = new AtomicInteger();

        Cold(int capacity) {
            this.keys = new long[capacity];
            this.to = new Subset[capacity];
        }

        int capacity() {
            return keys.length;
        }

        /** What a table of {@code capacity} places holds, counted as the {@link Budget} counts: a
         *  key is a {@code long}, two, and where it leads a reference, one. */
        static long holds(int capacity) {
            return 3L * capacity;
        }

        long holds() {
            return holds(capacity());
        }

        private int slot(long key) {
            return (int) ((key * 0x9E37_79B9_7F4A_7C15L) >>> 40) & (keys.length - 1);
        }

        /** Where the step kept by {@code key} leads, or null where it is not kept, asking before each
         *  place it looks in. */
        @Nullable Subset get(long key, @Nullable Checkpoint checkpoint) {
            int mask = keys.length - 1;
            int at = slot(key);
            for (int probe = 0; probe <= mask; probe++) {
                ask(checkpoint);
                long held = keys[at];
                if (held == key) {
                    return to[at];
                }
                if (held == 0) {
                    return null;
                }
                at = (at + 1) & mask;
            }
            return null;
        }

        /** Keeps that the step kept by {@code key} leads to {@code target}, and answers whether it
         *  did: not where the table is half full and the key not in it, asking before each place it
         *  looks in. */
        boolean put(long key, Subset target, @Nullable Checkpoint checkpoint) {
            int mask = keys.length - 1;
            int at = slot(key);
            for (int probe = 0; probe <= mask; probe++) {
                ask(checkpoint);
                long held = (long) KEYS.getVolatile(keys, at);
                if (held == 0) {
                    if (2 * count.get() >= keys.length) {
                        return false;
                    }
                    if (KEYS.compareAndSet(keys, at, 0L, key)) {
                        count.incrementAndGet();
                        held = key;
                    } else {
                        held = (long) KEYS.getVolatile(keys, at);
                    }
                }
                if (held == key) {
                    TO.setRelease(to, at, target);
                    return true;
                }
                at = (at + 1) & mask;
            }
            return false;
        }

        /** Puts every step this table keeps in {@code larger}, asking before each place. */
        void copyInto(Cold larger, @Nullable Checkpoint checkpoint) {
            for (int at = 0; at < keys.length; at++) {
                ask(checkpoint);
                long held = (long) KEYS.getVolatile(keys, at);
                @Nullable Subset target = (Subset) TO.getAcquire(to, at);
                if (held != 0 && target != null) {
                    larger.put(held, target, checkpoint);
                }
            }
        }

        /** How many steps it keeps. */
        int known() {
            int known = 0;
            for (int at = 0; at < keys.length; at++) {
                if ((long) KEYS.getVolatile(keys, at) != 0 && TO.getAcquire(to, at) != null) {
                    known++;
                }
            }
            return known;
        }
    }

    /**
     * What a walk as sets of states is held in, as large as the machine: the
     * states it is in and is going into, which of them it has put in this round, and those it has
     * yet to look past for steps for no character.
     *
     * <p>Every walk puts states in through {@link #close}, with sets kept or without, so nothing is
     * held here that only one of them needs. What only keeping a set needs, its hash and whether it
     * accepts, is worked out of the set where it is kept ({@link #hashOf}, {@link #acceptsAny}).
     *
     * <p>A pattern keeps one for the next walk, as the last walk to finish left it, so a walk does
     * not make room the size of the machine each time ({@link #room}, {@link #leave}). Its rounds go
     * on from one walk to the next, so nothing a walk before put in it is taken as put in by this
     * one.
     */
    private static final class Room {

        int[] here;
        int[] there;
        final int[] seen;
        final int[] pending;
        int round;

        Room(int states) {
            this.here = new int[states];
            this.there = new int[states];
            this.seen = new int[states];
            this.pending = new int[states];
        }

        /** Starts a round no state has been put in yet. Once in four billion, where the rounds come
         *  round to nought, every state is marked as put in none. */
        void nextRound() {
            if (++round == 0) {
                Arrays.fill(seen, 0);
                round = 1;
            }
        }
    }

    /** The sum of {@link #scatter} over the first {@code count} of {@code states}, the same in
     *  whatever order they are in, asking before each. It is summed where a set is looked up and
     *  not as each state is put in, so a walk that keeps no sets does not sum it. */
    private static int hashOf(int[] states, int count, @Nullable Checkpoint checkpoint) {
        int hash = 0;
        for (int i = 0; i < count; i++) {
            ask(checkpoint);
            hash += scatter(states[i]);
        }
        return hash;
    }

    /** Whether a walk may stop at any of the first {@code count} of {@code states}, asking before
     *  each: asked where a set is kept and where a walk without kept sets ends, and not as each state
     *  is put in. */
    private static boolean acceptsAny(boolean[] accepting, int[] states, int count,
                                      @Nullable Checkpoint checkpoint) {
        for (int i = 0; i < count; i++) {
            ask(checkpoint);
            if (accepting[states[i]]) {
                return true;
            }
        }
        return false;
    }

    /** A state's part of the hash of a set it is in, which is the same in whatever order the set's
     *  states are put in. */
    private static int scatter(int state) {
        int mixed = state * 0x9E3779B9;
        return mixed ^ (mixed >>> 15);
    }

    /**
     * Every state the walk is in at once, over a machine walked as sets of states.
     *
     * <p>A state is put in the walk once for each character however many ways lead to it, which is
     * what keeps a character's work to the machine's size: {@code seen} holds the character a state
     * was last put in for. Once the subject is read, the states the walk ended in are looked through
     * for one it may stop at, and that is asked about as the rest of the walk is.
     */
    private boolean spread(Steps steps, String value, @Nullable Checkpoint checkpoint) {
        // The room a walk is held in is as large as the machine, and is taken only once asked.
        ask(checkpoint);
        Room room = room();
        try {
            room.nextRound();
            int count = close(steps, 0, room.here, 0, room, checkpoint);
            int at = 0;
            while (at < value.length()) {
                ask(checkpoint);
                if (count == 0) {
                    return false;
                }
                int symbol = value.codePointAt(at);
                at += Character.charCount(symbol);
                count = move(steps, room.here, count, symbol, room, checkpoint);
                int[] was = room.here;
                room.here = room.there;
                room.there = was;
            }
            return acceptsAny(accepting, room.here, count, checkpoint);
        } finally {
            leave(room);
        }
    }

    /** A room as large as this machine for a walk to work in: the one the last walk to finish left,
     *  where no other walk has taken it, and otherwise a new one. */
    private Room room() {
        Room left = spare.getAndSet(null);
        return left != null ? left : new Room(accepting.length);
    }

    /** Leaves {@code room} for the next walk, where no other walk has left one; otherwise it is let
     *  go. A walk stopped part of the way leaves it too: what it held is never read again, as
     *  {@link Room#nextRound} says. */
    private void leave(Room room) {
        spare.compareAndSet(null, room);
    }

    /** {@code from} and every state it reaches for no character, put into {@code into} after its
     *  first {@code count}, where {@code room} has not put them in this round; answers how many it
     *  holds now. */
    private int close(Steps steps, int from, int[] into, int count, Room room,
                      @Nullable Checkpoint checkpoint) {
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
            for (int to : steps.free()[state]) {
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
     * An image of P1 being written, by whoever holds a machine.
     *
     * <p>Here beside the reader, so the format has one owner: a writer elsewhere and a reader here
     * would be two accounts of it, and nothing would hold them to each other.
     *
     * <p>Private to this package. It writes every machine as one that is not deterministic, whatever
     * the machine is: an image of P1 that says its machine is deterministic is one a reader holds to
     * that at a cost the image's length does not bound, so no release writes one any more, and a
     * deterministic machine is written as P2 ({@link #imageOfP2}). Such images an earlier release
     * wrote are still read.
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
    static final class P1Writer {

        private final long mostCharacters;
        private long characters;
        private final List<String> sets = new ArrayList<>();
        private final Map<String, Integer> known = new HashMap<>();
        private final List<Boolean> accepting = new ArrayList<>();
        private final List<List<int[]>> steps = new ArrayList<>();
        private final List<List<Integer>> free = new ArrayList<>();

        /**
         * @param mostCharacters the most characters the image may take
         */
        public P1Writer(long mostCharacters) {
            this.mostCharacters = mostCharacters;
            // The format's marker, the kind, and the two counts written before what they count, at
            // their widest.
            this.characters = ImageFormat.P1.marker().length() + 1 + 2 + 2 * NUMBER;
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
            out.append(ImageFormat.P1.marker()).append(',');
            out.append(0).append(',').append(sets.size());
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
            return chunks(out);
        }
    }

    /**
     * {@code rows} written as an image of P2, cut into strings of at most {@link #CHUNK} characters;
     * or null where it would take more than {@code mostCharacters}.
     *
     * <p>Here beside the reader, so the format has one owner. The rows are written as they are: they
     * are pieces and spans as P2 writes them, each next to one of another class or leading to
     * another state, so nothing is worked out here and nothing here can write an image that is not
     * one. Bounded as it is written and not after: writing stops at the first state that takes the
     * image past its limit.
     */
    static @Nullable List<String> imageOfP2(ClassRows rows, long mostCharacters) {
        StringBuilder out = new StringBuilder(ImageFormat.P2.marker());
        for (int piece = 0; piece < rows.pieces() && out.length() <= mostCharacters; piece++) {
            out.append(',').append(rows.last(piece)).append(',').append(rows.classOfPiece(piece));
        }
        out.append(',').append(rows.states());
        for (int state = 0; state < rows.states() && out.length() <= mostCharacters; state++) {
            out.append(',').append(rows.stopsAt(state) ? 1 : 0);
            for (int span = 0; span < rows.spans(state); span++) {
                out.append(',').append(rows.end(state, span)).append(',').append(rows.to(state, span));
            }
        }
        return out.length() <= mostCharacters ? chunks(out) : null;
    }

    /** {@code image} cut into strings of at most {@link #CHUNK} characters. */
    private static List<String> chunks(CharSequence image) {
        List<String> out = new ArrayList<>();
        for (int at = 0; at < image.length(); at += CHUNK) {
            out.add(image.subSequence(at, Math.min(image.length(), at + CHUNK)).toString());
        }
        return List.copyOf(out);
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

        /**
         * The next number: 0, or digits that do not begin with 0, ended by a comma or by the end of
         * the image. A comma is followed by a number, so an image does not end with one. A number of
         * P1 or P2 is at most 2,147,483,647 wherever it stands ({@code image/P1.md},
         * {@code image/P2.md}), and a larger one is refused here, before what its place allows is
         * asked.
         */
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
                if (value > (Integer.MAX_VALUE - (digit - '0')) / 10) {
                    throw new IllegalArgumentException("an image writes no number past "
                            + Integer.MAX_VALUE + ": " + text.substring(start, Math.min(at, start + 20)));
                }
                value = value * 10 + (digit - '0');
            }
            if (at == start) {
                throw new IllegalArgumentException("an image writes no empty number");
            }
            if (text.charAt(start) == '0' && at - start > 1) {
                throw new IllegalArgumentException("an image writes no number but 0 beginning with 0: "
                        + text.substring(start, Math.min(at, start + 20)));
            }
            if (at == text.length() - 1) {
                throw new IllegalArgumentException("an image ends with a number, not a comma");
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
