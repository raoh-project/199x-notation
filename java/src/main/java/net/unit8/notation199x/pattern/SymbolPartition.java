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
 * <p>Working the classes out looks, for each set, at the pieces of the symbols it covers or at
 * those it leaves, whichever are fewer: a set and what it leaves cut the symbols the same way. So
 * a class written negated, which leaves a piece or two, costs those, and only sets nested in one
 * another, each covering about half of what the others cut, come to as many as the sets times the
 * pieces. It is counted on a {@link Meter.Making}, a set at a time before the set is looked at, and
 * given up where that runs out. Saying which classes each set holds ({@link #classesOf}) is the
 * pieces each covers again, and is worked out only where it is asked for.
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
    /** For each set, in the order they were given, the classes it holds, ascending and each once;
     *  null where they were not asked for ({@link #cutBy}). */
    private final int @Nullable [][] classesOf;

    private SymbolPartition(int[] cuts, int[] classOf, int count, int[] least, int @Nullable [][] classesOf) {
        this.cuts = cuts;
        this.classOf = classOf;
        this.count = count;
        this.least = least;
        this.classesOf = classesOf;
    }

    /**
     * The classes {@code sets} cut the symbols into, with the classes each set holds, or null where
     * working them out is more than {@code making} allows.
     *
     * @param sets   each set, as ascending {@code from, to} pairs of scalar values
     * @param making what working them out may look at, counted before it is looked at
     */
    static @Nullable SymbolPartition of(List<int[]> sets, Meter.Making making) {
        return of(sets, making, true);
    }

    /**
     * The classes {@code sets} cut the symbols into, without the classes each set holds, or null
     * where working them out is more than {@code making} allows: for a walk that looks a character
     * up as its class and asks no set which classes it holds.
     *
     * @param sets   each set, as ascending {@code from, to} pairs of scalar values
     * @param making what working them out may look at, counted before it is looked at
     */
    static @Nullable SymbolPartition cutBy(List<int[]> sets, Meter.Making making) {
        return of(sets, making, false);
    }

    private static @Nullable SymbolPartition of(List<int[]> sets, Meter.Making making, boolean withSets) {
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
        Splitting splitting = new Splitting(pieces + 1);
        long covered = 0;
        int set = 0;
        for (int[] ranges : sets) {
            // Where each of the set's ranges begins and ends among the pieces, which every set's
            // ends are among, so that how many pieces it covers is known before any is moved.
            int[] ends = new int[ranges.length];
            int many = 0;
            for (int at = 0; at < ranges.length; at += 2) {
                ends[at] = Arrays.binarySearch(cuts, ranges[at]);
                ends[at + 1] = Arrays.binarySearch(cuts, ranges[at + 1] + 1);
                many += ends[at + 1] - ends[at];
            }
            covered += many;
            // What the set covers and what it leaves are moved apart the same either way, so the
            // fewer of them are moved.
            boolean leaving = 2L * many > pieces;
            if (!making.work(1L + (leaving ? pieces - many : many))) {
                return null;
            }
            int left = 0;
            for (int at = 0; at <= ranges.length; at += 2) {
                if (leaving) {
                    // What is left before this range, and after the last.
                    splitting.move(classOf, left, at < ranges.length ? ends[at] : pieces, set);
                } else if (at < ranges.length) {
                    splitting.move(classOf, ends[at], ends[at + 1], set);
                }
                if (at < ranges.length) {
                    left = ends[at + 1];
                }
            }
            set++;
        }
        int classes = splitting.classes;
        // Numbering the classes again looks at each piece and each class a few times, and saying
        // which classes each set holds is the pieces it covers again.
        if (!making.work(2L * pieces + 2L * classes + (withSets ? covered : 0))) {
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
        if (!withSets) {
            return new SymbolPartition(cuts, classOf, count, Arrays.copyOf(least, count), null);
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

    /**
     * The classes made as the sets move pieces apart: for each class, the class made for what the
     * set moving pieces now takes from it, and which set that was.
     */
    private static final class Splitting {

        int classes = 1;
        int[] into;
        int[] by;

        Splitting(int room) {
            into = new int[room];
            by = new int[room];
            Arrays.fill(by, -1);
        }

        /** Moves each piece from {@code from} to before {@code past} out of the class it is in,
         *  into the class made for what set {@code set} takes from that one, made the first time
         *  the set takes from it. */
        void move(int[] classOf, int from, int past, int set) {
            for (int piece = from; piece < past; piece++) {
                int was = classOf[piece];
                if (by[was] != set) {
                    by[was] = set;
                    if (classes == into.length) {
                        into = Arrays.copyOf(into, classes * 2);
                        int grown = by.length;
                        by = Arrays.copyOf(by, classes * 2);
                        Arrays.fill(by, grown, by.length, -1);
                    }
                    into[was] = classes++;
                }
                classOf[piece] = into[was];
            }
        }
    }

    /**
     * The classes an image of P2 writes, as its pieces: piece {@code p} ends at {@code lasts[p]},
     * begins at the scalar value after the one before it ends, and is in class {@code classOf[p]}.
     *
     * <p>The image numbers its classes in the order their first piece comes in, which is the order
     * this numbers them in, so they are taken as they are. A piece of the image may hold scalar
     * values on both sides of the surrogates, and is cut in two around them here. No set is given,
     * so none has classes ({@link #classesOf}).
     *
     * @param lasts   where each piece ends, ascending, the last at U+10FFFF
     * @param classOf the class of each piece
     * @param count   how many classes there are
     */
    static SymbolPartition ofPieces(int[] lasts, int[] classOf, int count) {
        int[] cuts = new int[lasts.length + 3];
        int[] classes = new int[lasts.length + 2];
        int[] least = new int[count];
        Arrays.fill(least, -1);
        int pieces = 0;
        int from = 0;
        for (int at = 0; at < lasts.length; at++) {
            int of = classOf[at];
            if (from == SURROGATES_FROM) {
                cuts[pieces] = SURROGATES_FROM;
                classes[pieces++] = -1;
                from = SURROGATES_PAST;
            }
            if (least[of] < 0) {
                least[of] = from;
            }
            cuts[pieces] = from;
            classes[pieces++] = of;
            if (from < SURROGATES_FROM && lasts[at] >= SURROGATES_PAST) {
                cuts[pieces] = SURROGATES_FROM;
                classes[pieces++] = -1;
                cuts[pieces] = SURROGATES_PAST;
                classes[pieces++] = of;
            }
            from = lasts[at] + 1;
        }
        cuts[pieces] = Character.MAX_CODE_POINT + 1;
        return new SymbolPartition(Arrays.copyOf(cuts, pieces + 1), Arrays.copyOf(classes, pieces),
                count, least, new int[0][]);
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
     *  be written to. Asked only of classes made with them ({@link #of}). */
    int[] classesOf(int set) {
        if (classesOf == null) {
            throw new IllegalStateException("which classes a set holds is asked of classes made without it");
        }
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
