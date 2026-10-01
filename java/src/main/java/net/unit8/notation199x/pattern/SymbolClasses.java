package net.unit8.notation199x.pattern;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The symbols a machine's steps tell apart, put into classes: two symbols are in one class where
 * every set a step is over holds both or neither. A walk asks which class a character is in and
 * then where that class leads, so a state's steps are one row as wide as the classes, and not a
 * search of its sets.
 *
 * <p>A character of the Basic Multilingual Plane is two lookups, one for the block of 256 it is in
 * and one inside that block, and blocks that hold the same classes are held once. A character past
 * it is a search of where the classes change there, which is short for every set the database
 * writes.
 *
 * <p>No set a step is over holds a surrogate, so the surrogates are in a class with the symbols no
 * set holds, and from every state it leads nowhere: half a surrogate pair needs no rule of its own
 * past being looked up.
 *
 * <p>Working the classes out looks at each piece of the symbols each set covers, which for sets
 * nested in one another is as many as the sets times the pieces. So it is counted on a
 * {@link Meter}, as making a machine deterministic is, and given up where that runs out: the classes
 * are a way to walk faster and decide no answer, and a machine without them is walked by its sets.
 */
final class SymbolClasses {

    /** The most classes this holds, each a {@code char}. A machine whose sets cut the symbols into
     *  more has none, and is walked by its sets. */
    static final int MOST = Character.MAX_VALUE;

    private static final int BMP = 0x10000;
    private static final int BLOCK = 256;
    private static final int ASCII = 128;

    private final int count;
    private final char[] ascii;
    /** For each block of 256 in the Basic Multilingual Plane, where its classes begin in
     *  {@link #blocks}. */
    private final char[] index;
    private final char[] blocks;
    /** Past the Basic Multilingual Plane, where each run of one class begins, ascending. */
    private final int[] beyondFrom;
    private final char[] beyond;
    /** A symbol of each class, which every set holds as it holds the rest of the class. */
    private final int[] some;
    /** For each class, which ASCII characters it holds: those below 64, and the rest. */
    private final long[] low;
    private final long[] high;
    /** For each class, at {@code class * 2} and the one after, the two longest runs it holds past
     *  ASCII inside the Basic Multilingual Plane, as where each begins and ends; a run it does not
     *  have begins after it ends. */
    private final char[] runFrom;
    private final char[] runTo;

    private SymbolClasses(int count, char[] ascii, char[] index, char[] blocks, int[] beyondFrom,
                          char[] beyond, int[] some, long[] low, long[] high, char[] runFrom,
                          char[] runTo) {
        this.count = count;
        this.ascii = ascii;
        this.index = index;
        this.blocks = blocks;
        this.beyondFrom = beyondFrom;
        this.beyond = beyond;
        this.some = some;
        this.low = low;
        this.high = high;
        this.runFrom = runFrom;
        this.runTo = runTo;
    }

    /**
     * The classes {@code sets} cut the symbols into, or null where they are more than
     * {@link #MOST} or working them out is more than {@code making} allows.
     *
     * @param sets   each set a step is over, as ascending {@code from, to} pairs of scalar values
     * @param making what working them out may look at, counted before it is looked at
     */
    static @Nullable SymbolClasses of(Collection<int[]> sets, Meter.Making making) {
        // The symbols are first cut wherever a set begins or ends, and the pieces are then put
        // together where no set tells them apart.
        long bounds = 2;
        for (int[] ranges : sets) {
            bounds += ranges.length;
        }
        if (!making.work(bounds)) {
            return null;
        }
        int[] cuts = cuts(sets);
        int pieces = cuts.length - 1;
        int[] classOf = new int[pieces];
        int classes = 1;
        int[] splitInto = new int[pieces + 1];
        int[] splitBy = new int[pieces + 1];
        Arrays.fill(splitBy, -1);
        int set = 0;
        for (int[] ranges : sets) {
            // Each set moves the pieces it holds out of the class they were in, into a class made
            // for what it took from that one.
            for (int at = 0; at < ranges.length; at += 2) {
                int piece = Arrays.binarySearch(cuts, ranges[at]);
                for (; cuts[piece] <= ranges[at + 1]; piece++) {
                    if (!making.work(1)) {
                        return null;
                    }
                    int was = classOf[piece];
                    if (splitBy[was] != set) {
                        splitBy[was] = set;
                        if (classes == splitInto.length) {
                            splitInto = Arrays.copyOf(splitInto, classes * 2);
                            int grown = splitBy.length;
                            splitBy = Arrays.copyOf(splitBy, classes * 2);
                            Arrays.fill(splitBy, grown, splitBy.length, -1);
                        }
                        splitInto[was] = classes++;
                    }
                    classOf[piece] = splitInto[was];
                }
            }
            set++;
        }
        // What is left is looked at a few times over each piece and each class, and once over the
        // plane, whatever the sets are.
        if (!making.work(6L * pieces + 4L * classes + BMP)) {
            return null;
        }
        // A class every piece was moved out of holds nothing, and the rest are numbered again in
        // the order their first symbol comes in.
        int[] renumbered = new int[classes];
        Arrays.fill(renumbered, -1);
        int count = 0;
        for (int piece = 0; piece < pieces; piece++) {
            if (renumbered[classOf[piece]] < 0) {
                renumbered[classOf[piece]] = count++;
            }
            classOf[piece] = renumbered[classOf[piece]];
        }
        if (count > MOST) {
            return null;
        }
        int[] some = new int[count];
        Arrays.fill(some, -1);
        for (int piece = 0; piece < pieces; piece++) {
            if (some[classOf[piece]] < 0) {
                some[classOf[piece]] = cuts[piece];
            }
        }
        // A block that one class fills is held once for that class, and any other block once for
        // what it holds.
        char[] index = new char[BMP / BLOCK];
        Map<Integer, Character> filled = new HashMap<>();
        Map<String, Character> held = new HashMap<>();
        StringBuilder blocks = new StringBuilder();
        char[] block = new char[BLOCK];
        int piece = 0;
        for (int each = 0; each < index.length; each++) {
            int begins = each * BLOCK;
            while (cuts[piece + 1] <= begins) {
                piece++;
            }
            Character at;
            if (cuts[piece + 1] >= begins + BLOCK) {
                at = filled.get(classOf[piece]);
                if (at == null) {
                    Arrays.fill(block, (char) classOf[piece]);
                    at = (char) blocks.length();
                    filled.put(classOf[piece], at);
                    blocks.append(block);
                }
            } else {
                int inside = piece;
                for (int unit = 0; unit < BLOCK; unit++) {
                    while (cuts[inside + 1] <= begins + unit) {
                        inside++;
                    }
                    block[unit] = (char) classOf[inside];
                }
                String written = new String(block);
                at = held.get(written);
                if (at == null) {
                    at = (char) blocks.length();
                    held.put(written, at);
                    blocks.append(block);
                }
            }
            index[each] = at;
        }
        char[] ascii = new char[ASCII];
        for (int unit = 0; unit < ASCII; unit++) {
            ascii[unit] = blocks.charAt(index[0] + unit);
        }
        int[] beyondFrom = new int[pieces];
        char[] beyond = new char[pieces];
        int runs = 0;
        for (piece = 0; piece < pieces; piece++) {
            if (cuts[piece + 1] <= BMP) {
                continue;
            }
            if (runs > 0 && beyond[runs - 1] == classOf[piece]) {
                continue;
            }
            beyondFrom[runs] = Math.max(cuts[piece], BMP);
            beyond[runs++] = (char) classOf[piece];
        }
        long[] low = new long[count];
        long[] high = new long[count];
        for (int unit = 0; unit < ASCII; unit++) {
            if (unit < 64) {
                low[ascii[unit]] |= 1L << unit;
            } else {
                high[ascii[unit]] |= 1L << unit;
            }
        }
        // A class's runs past ASCII, pieces of it beside each other taken as one, of which the two
        // longest are kept.
        char[] runFrom = new char[count * 2];
        char[] runTo = new char[count * 2];
        Arrays.fill(runFrom, (char) 1);
        for (piece = 0; piece < pieces; ) {
            int begins = Math.max(cuts[piece], ASCII);
            int of = classOf[piece];
            while (piece < pieces && classOf[piece] == of) {
                piece++;
            }
            int ends = Math.min(cuts[piece], BMP) - 1;
            if (ends < begins) {
                continue;
            }
            int longest = of * 2;
            if (ends - begins > runTo[longest] - runFrom[longest]) {
                runFrom[longest + 1] = runFrom[longest];
                runTo[longest + 1] = runTo[longest];
                runFrom[longest] = (char) begins;
                runTo[longest] = (char) ends;
            } else if (ends - begins > runTo[longest + 1] - runFrom[longest + 1]) {
                runFrom[longest + 1] = (char) begins;
                runTo[longest + 1] = (char) ends;
            }
        }
        return new SymbolClasses(count, ascii, index, blocks.toString().toCharArray(),
                Arrays.copyOf(beyondFrom, runs), Arrays.copyOf(beyond, runs), some, low, high,
                runFrom, runTo);
    }

    /** Every symbol where a set begins or where one ends before the last, ascending, between
     *  nought and one past the last symbol. */
    private static int[] cuts(Collection<int[]> sets) {
        int size = 2;
        for (int[] ranges : sets) {
            size += ranges.length;
        }
        int[] cuts = new int[size];
        int count = 0;
        cuts[count++] = 0;
        cuts[count++] = Character.MAX_CODE_POINT + 1;
        for (int[] ranges : sets) {
            for (int at = 0; at < ranges.length; at += 2) {
                cuts[count++] = ranges[at];
                cuts[count++] = ranges[at + 1] + 1;
            }
        }
        Arrays.sort(cuts, 0, count);
        int distinct = 0;
        for (int at = 0; at < count; at++) {
            if (distinct == 0 || cuts[at] != cuts[distinct - 1]) {
                cuts[distinct++] = cuts[at];
            }
        }
        return Arrays.copyOf(cuts, distinct);
    }

    /** How many classes there are. */
    int count() {
        return count;
    }

    /** A symbol of class {@code of}, which every set holds or does not as it does the rest. */
    int some(int of) {
        return some[of];
    }

    /**
     * The class of the character {@code value} has at {@code at}, or -1 where that is half a
     * surrogate pair. A character is one unit of the string, or two where it is a whole pair, and
     * how far to go on is the caller's: two past a high surrogate, which is half a pair where it is
     * not the first of two.
     */
    int at(String value, int at) {
        char unit = value.charAt(at);
        if (unit < ASCII) {
            return ascii[unit];
        }
        if (!Character.isSurrogate(unit)) {
            return blocks[index[unit >>> 8] + (unit & 0xFF)];
        }
        if (Character.isHighSurrogate(unit) && at + 1 < value.length()) {
            char low = value.charAt(at + 1);
            if (Character.isLowSurrogate(low)) {
                return beyond(Character.toCodePoint(unit, low));
            }
        }
        return -1;
    }

    /** The class of an ASCII character, {@code unit} being below 128. */
    int ascii(char unit) {
        return ascii[unit];
    }

    /** The class of {@code symbol}. */
    int of(int symbol) {
        return symbol < BMP ? blocks[index[symbol >>> 8] + (symbol & 0xFF)] : beyond(symbol);
    }

    /**
     * Characters a walk stays where it is on, looked up without a table: the ASCII ones as a bit
     * each, and up to two runs past them in the Basic Multilingual Plane, each as where it begins
     * and ends. A run that holds none begins after it ends.
     *
     * <p>Some characters it stays on may not be among them, and those are walked one at a time. So
     * it is made a class at a time ({@link #with}), each in a few steps, and never by looking over
     * every class or every run.
     */
    record Stay(long low, long high, char from, char to, char otherFrom, char otherTo) {

        /** Characters a walk stays on none of. */
        static final Stay NONE = new Stay(0, 0, (char) 1, (char) 0, (char) 1, (char) 0);

        /**
         * These and the characters of class {@code each} of {@code classes}: the ASCII ones of both,
         * and of the runs of both, the two that come first, a longer run before a shorter and of
         * two as long the one that begins first.
         *
         * <p>So what a walk stays on is the same for the same classes, in whatever order they are
         * added and however often one is: taking the first two of all the runs of some classes is
         * taking the first two of the first two of each. Walks on many threads add classes to one
         * set at once ({@link StringPattern}), each to what the others added, and come to the one
         * answer whichever adds last.
         */
        Stay with(SymbolClasses classes, int each) {
            char[] froms = {from, otherFrom, classes.runFrom[each * 2], classes.runFrom[each * 2 + 1]};
            char[] tos = {to, otherTo, classes.runTo[each * 2], classes.runTo[each * 2 + 1]};
            int first = -1;
            int second = -1;
            for (int at = 0; at < 4; at++) {
                if (tos[at] < froms[at]) {
                    continue;
                }
                if (first < 0 || before(froms, tos, at, first)) {
                    if (first >= 0 && (froms[first] != froms[at] || tos[first] != tos[at])) {
                        second = first;
                    }
                    first = at;
                } else if ((froms[first] != froms[at] || tos[first] != tos[at])
                        && (second < 0 || before(froms, tos, at, second))) {
                    second = at;
                }
            }
            return new Stay(low | classes.low[each], high | classes.high[each],
                    first < 0 ? (char) 1 : froms[first], first < 0 ? (char) 0 : tos[first],
                    second < 0 ? (char) 1 : froms[second], second < 0 ? (char) 0 : tos[second]);
        }

        /** Whether run {@code one} comes before run {@code other}: it is longer, or as long and
         *  begins first. */
        private static boolean before(char[] froms, char[] tos, int one, int other) {
            int longer = (tos[one] - froms[one]) - (tos[other] - froms[other]);
            return longer > 0 || (longer == 0 && froms[one] < froms[other]);
        }

        /** How far from {@code at} the characters of {@code value} keep a walk where it is. */
        int over(String value, int at) {
            long low = this.low;
            long high = this.high;
            int from = this.from;
            int to = this.to;
            int otherFrom = this.otherFrom;
            int otherTo = this.otherTo;
            int length = value.length();
            // One loop for each of the three, each with one test, gone round again for as long as
            // one of them goes on: text is mostly in one of them for long stretches.
            while (at < length) {
                int was = at;
                while (at < length) {
                    char unit = value.charAt(at);
                    if (unit >= ASCII || (((unit < 64 ? low : high) >>> unit) & 1) == 0) {
                        break;
                    }
                    at++;
                }
                while (at < length) {
                    char unit = value.charAt(at);
                    if (unit < from || unit > to) {
                        break;
                    }
                    at++;
                }
                while (at < length) {
                    char unit = value.charAt(at);
                    if (unit < otherFrom || unit > otherTo) {
                        break;
                    }
                    at++;
                }
                if (at == was) {
                    break;
                }
            }
            return at;
        }
    }

    private int beyond(int symbol) {
        int low = 0;
        int high = beyondFrom.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (beyondFrom[mid] <= symbol) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return beyond[low];
    }
}
