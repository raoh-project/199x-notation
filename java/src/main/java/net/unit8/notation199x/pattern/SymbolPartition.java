package net.unit8.notation199x.pattern;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The classes some sets cut the symbols into: two symbols are in one class where every set holds
 * both or neither, and two classes are told apart by at least one set.
 *
 * <p>What a machine tells apart, and so the alphabet a deterministic machine is made over and a
 * walk looks a character up in. Not the runs a set's ranges leave: a set of scattered characters
 * cuts the symbols into thousands of runs, and every one of them is in or out of it, so they are two
 * classes. Making a machine deterministic over the runs made each of its rows as wide as the runs;
 * over the classes it is as wide as what the machine can tell apart.
 *
 * <p>The symbols are the scalar values, so no class holds a surrogate. Classes are numbered in the
 * order their least symbol comes in, which is the order a walk over the symbols first meets them —
 * the order a canonical machine is numbered by ({@link Automaton#canonical}). How a class is looked
 * up in a string is not this, and neither is any limit on how many there may be: those are
 * {@link SymbolClasses}.
 *
 * <p>Working the classes out looks at each piece of the symbols each set covers, which for sets
 * nested in one another is as many as the sets times the pieces. So it is counted on a
 * {@link Meter.Making}, and given up where that runs out.
 */
final class SymbolPartition {

    private static final int SURROGATES_FROM = 0xD800;
    private static final int SURROGATES_PAST = 0xE000;

    /** Where each piece begins, ascending, from nought to one past the last symbol: piece
     *  {@code p} is from {@code cuts[p]} to before {@code cuts[p + 1]}. Cut at both ends of the
     *  surrogates, so a piece is either all surrogates or holds none. */
    private final int[] cuts;
    /** For each piece the class it is in, or -1 where it is surrogates. */
    private final int[] classOf;
    private final int count;
    /** For each class, its least symbol. */
    private final int[] least;
    /** For each set, in the order they were given, the classes it holds, ascending and each once. */
    private final int[][] classesOf;

    private SymbolPartition(int[] cuts, int[] classOf, int count, int[] least, int[][] classesOf) {
        this.cuts = cuts;
        this.classOf = classOf;
        this.count = count;
        this.least = least;
        this.classesOf = classesOf;
    }

    /**
     * The classes {@code sets} cut the symbols into, or null where working them out is more than
     * {@code making} allows.
     *
     * @param sets   each set, as ascending {@code from, to} pairs of scalar values
     * @param making what working them out may look at, counted before it is looked at
     */
    static @Nullable SymbolPartition of(List<int[]> sets, Meter.Making making) {
        long bounds = 4;
        for (int[] ranges : sets) {
            bounds += ranges.length;
        }
        if (!making.work(bounds)) {
            return null;
        }
        int[] cuts = cuts(sets);
        int pieces = cuts.length - 1;
        // The symbols are first cut wherever a set begins or ends, and each set then moves the
        // pieces it holds out of the class they were in, into a class made for what it took from
        // that one.
        int[] classOf = new int[pieces];
        int classes = 1;
        int[] splitInto = new int[pieces + 1];
        int[] splitBy = new int[pieces + 1];
        Arrays.fill(splitBy, -1);
        long covered = 0;
        int set = 0;
        for (int[] ranges : sets) {
            for (int at = 0; at < ranges.length; at += 2) {
                int piece = Arrays.binarySearch(cuts, ranges[at]);
                for (; cuts[piece] <= ranges[at + 1]; piece++) {
                    if (!making.work(1)) {
                        return null;
                    }
                    covered++;
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
        // Numbering the classes again looks at each piece and each class a few times, and saying
        // which classes each set holds is the pieces it covers again.
        if (!making.work(2L * pieces + 2L * classes + covered)) {
            return null;
        }
        // A class every piece was moved out of holds nothing, and the surrogates are no symbol, so
        // the rest are numbered again in the order their first symbol comes in.
        int[] renumbered = new int[classes];
        Arrays.fill(renumbered, -1);
        int count = 0;
        int[] least = new int[classes];
        for (int piece = 0; piece < pieces; piece++) {
            if (cuts[piece] >= SURROGATES_FROM && cuts[piece] < SURROGATES_PAST) {
                classOf[piece] = -1;
                continue;
            }
            if (renumbered[classOf[piece]] < 0) {
                least[count] = cuts[piece];
                renumbered[classOf[piece]] = count++;
            }
            classOf[piece] = renumbered[classOf[piece]];
        }
        // A set that holds a class holds its least symbol, which is where a walk over the set's
        // pieces in order first meets the class; and the classes are numbered in the order of their
        // least symbols. So the classes a set holds come in ascending order as its pieces are
        // walked, each the first time it is met, and need no putting in order.
        int[][] classesOf = new int[sets.size()][];
        int[] seenBy = new int[count];
        Arrays.fill(seenBy, -1);
        int[] held = new int[count];
        set = 0;
        for (int[] ranges : sets) {
            int many = 0;
            for (int at = 0; at < ranges.length; at += 2) {
                int piece = Arrays.binarySearch(cuts, ranges[at]);
                for (; cuts[piece] <= ranges[at + 1]; piece++) {
                    int of = classOf[piece];
                    if (of >= 0 && seenBy[of] != set) {
                        seenBy[of] = set;
                        held[many++] = of;
                    }
                }
            }
            classesOf[set++] = Arrays.copyOf(held, many);
        }
        return new SymbolPartition(cuts, classOf, count, Arrays.copyOf(least, count), classesOf);
    }

    /** Every symbol where a set begins or where one ends before the last, and both ends of the
     *  surrogates, ascending, between nought and one past the last symbol. */
    private static int[] cuts(List<int[]> sets) {
        int size = 4;
        for (int[] ranges : sets) {
            size += ranges.length;
        }
        int[] cuts = new int[size];
        int count = 0;
        cuts[count++] = 0;
        cuts[count++] = SURROGATES_FROM;
        cuts[count++] = SURROGATES_PAST;
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

    /** The least symbol of class {@code of}, which every set holds or does not as it does the rest. */
    int least(int of) {
        return least[of];
    }

    /** The classes set {@code set} holds, ascending and each once: the set, as this sees it. Not to
     *  be written to. */
    int[] classesOf(int set) {
        return classesOf[set];
    }

    /** The class {@code symbol} is in, or -1 where it is a surrogate, which is in none. A search of
     *  where the pieces begin. */
    int classAt(int symbol) {
        int low = 0;
        int high = cuts.length - 2;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (cuts[mid] <= symbol) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return classOf[low];
    }

    /** Each class as the symbols it holds, in the order the classes are numbered; or null where
     *  writing them out, which looks at every piece, is more than {@code making} allows. */
    @Nullable List<CodePoints> classes(Meter.Making making) {
        if (!making.work(1L + pieces() + count)) {
            return null;
        }
        List<List<CodePoints.Range>> runs = new ArrayList<>(count);
        for (int each = 0; each < count; each++) {
            runs.add(new ArrayList<>());
        }
        for (int piece = 0; piece < pieces(); piece++) {
            if (classOf[piece] >= 0) {
                runs.get(classOf[piece]).add(new CodePoints.Range(cuts[piece], cuts[piece + 1] - 1));
            }
        }
        List<CodePoints> out = new ArrayList<>(count);
        for (List<CodePoints.Range> each : runs) {
            out.add(new CodePoints(each));
        }
        return out;
    }

    /** How many pieces the symbols were cut into, the surrogates among them. */
    int pieces() {
        return cuts.length - 1;
    }

    /** Where piece {@code piece} begins; it ends before where the next begins. */
    int from(int piece) {
        return cuts[piece];
    }

    /** The class piece {@code piece} is in, or -1 where it is surrogates. */
    int classOf(int piece) {
        return classOf[piece];
    }
}
