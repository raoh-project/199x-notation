package net.unit8.notation199x.pattern;

import java.util.Arrays;

/**
 * A deterministic machine as its classes and its rows: the scalar values cut into pieces, each in a
 * class, and each state's row as spans of classes, each span leading to one state. Every scalar
 * value is in one piece and every class in one span of each state, so a walk is in one state at a
 * time and every character leads somewhere.
 *
 * <p>This is what an image of P2 writes, held as it writes it, and the one account of a
 * deterministic machine here: an image of P2 is read into one ({@link StringPattern#of(java.util.List)}),
 * a machine made deterministic is turned into one ({@link #of(Automaton)}), and both are written
 * out ({@link StringPattern#imageOfP2}) and walked ({@link StringPattern#of(ClassRows)}) from it.
 * Nothing about it is worked out again from sets of symbols: that a symbol leads one way is what it
 * is made of, and not something asked of it.
 *
 * <p>As large as its pieces and spans, however many states and classes there are. The classes are
 * numbered in the order their first piece comes in, as {@link SymbolPartition} numbers them and as
 * P2 writes them.
 */
final class ClassRows {

    private static final int ASCII = 128;

    /** Where each piece ends, ascending, the last at U+10FFFF, and the class of each. */
    private final int[] lasts;
    private final int[] classOf;
    private final int classes;
    private final boolean[] accepting;
    /** For each state, the last class of each of its spans, ascending, the last the greatest
     *  class; and where each span leads. */
    private final int[][] ends;
    private final int[][] to;
    /** The class of each ASCII character, looked up rather than searched for. */
    private final int[] ascii;

    /**
     * Rows a caller has held to what {@link ClassRows} is: pieces ascending to U+10FFFF, none
     * ending at a surrogate, classes numbered in the order their first piece comes in, and for each
     * state spans ascending to the greatest class, each to a state there is. Not copied.
     */
    ClassRows(int[] lasts, int[] classOf, int classes, boolean[] accepting, int[][] ends, int[][] to) {
        this.lasts = lasts;
        this.classOf = classOf;
        this.classes = classes;
        this.accepting = accepting;
        this.ends = ends;
        this.to = to;
        this.ascii = new int[ASCII];
        int piece = 0;
        for (int c = 0; c < ASCII; c++) {
            while (lasts[piece] < c) {
                piece++;
            }
            ascii[c] = classOf[piece];
        }
    }

    /**
     * The rows of a machine made deterministic, from the classes and rows it holds
     * ({@link Automaton#classesOfWalk}, {@link Automaton#leadsTo}): two pieces of one class that the
     * surrogates or nothing come between are one piece, and two classes next to each other that
     * lead one state to the same state are one span.
     *
     * @param machine a machine made deterministic
     * @return its rows
     */
    static ClassRows of(Automaton machine) {
        SymbolPartition partition = machine.classesOfWalk();
        if (partition == null) {
            throw new IllegalArgumentException("a machine's rows are those of a machine made"
                    + " deterministic");
        }
        int[] lasts = new int[partition.pieces()];
        int[] classOf = new int[partition.pieces()];
        int pieces = 0;
        int greatest = -1;
        for (int piece = 0; piece < partition.pieces(); piece++) {
            int of = partition.classOf(piece);
            if (of < 0) {
                continue;
            }
            if (of > greatest + 1) {
                throw new IllegalStateException("the classes are numbered in the order their first"
                        + " piece comes in, and class " + of + " comes after " + greatest);
            }
            greatest = Math.max(greatest, of);
            int last = partition.from(piece + 1) - 1;
            if (pieces > 0 && classOf[pieces - 1] == of) {
                lasts[pieces - 1] = last;
            } else {
                lasts[pieces] = last;
                classOf[pieces++] = of;
            }
        }
        int width = partition.count();
        int states = machine.size();
        boolean[] accepting = new boolean[states];
        int[][] ends = new int[states][];
        int[][] to = new int[states][];
        int[] spanEnds = new int[width];
        int[] spanTo = new int[width];
        for (int state = 0; state < states; state++) {
            accepting[state] = machine.stopsAt(state);
            int spans = 0;
            for (int each = 0; each < width; each++) {
                int leads = machine.leadsTo(state, each);
                if (spans > 0 && spanTo[spans - 1] == leads) {
                    spanEnds[spans - 1] = each;
                } else {
                    spanEnds[spans] = each;
                    spanTo[spans++] = leads;
                }
            }
            ends[state] = Arrays.copyOf(spanEnds, spans);
            to[state] = Arrays.copyOf(spanTo, spans);
        }
        return new ClassRows(Arrays.copyOf(lasts, pieces), Arrays.copyOf(classOf, pieces), width,
                accepting, ends, to);
    }

    /** How many states there are; state 0 is where a walk begins. */
    int states() {
        return accepting.length;
    }

    /** How many classes there are. */
    int classes() {
        return classes;
    }

    /** Whether a walk that ends at {@code state} accepts. */
    boolean stopsAt(int state) {
        return accepting[state];
    }

    /** How many pieces there are, where piece {@code p} ends ({@link #last}) and its class. */
    int pieces() {
        return lasts.length;
    }

    int last(int piece) {
        return lasts[piece];
    }

    int classOfPiece(int piece) {
        return classOf[piece];
    }

    /** How many spans {@code state}'s row is, where each ends ({@link #end}) and where it leads. */
    int spans(int state) {
        return ends[state].length;
    }

    int end(int state, int span) {
        return ends[state][span];
    }

    int to(int state, int span) {
        return to[state][span];
    }

    /** The class {@code symbol}, a scalar value, is in: looked up where it is ASCII, and otherwise
     *  that of the first piece that ends at it or after. */
    int classAt(int symbol) {
        return symbol < ASCII ? ascii[symbol] : classOf[firstFrom(lasts, symbol)];
    }

    /** Where class {@code each} leads from {@code state}: the first span that ends at it or after. */
    int next(int state, int each) {
        return to[state][firstFrom(ends[state], each)];
    }

    /** The classes as {@link SymbolClasses} are made from: the pieces, cut around the surrogates. */
    SymbolPartition partition() {
        return SymbolPartition.ofPieces(lasts, classOf, classes);
    }

    /** Which states reach one that accepts, walked back from those: as long as the spans. */
    boolean[] live() {
        int states = accepting.length;
        int[] intoFrom = new int[states + 1];
        for (int[] each : to) {
            for (int leads : each) {
                intoFrom[leads + 1]++;
            }
        }
        for (int state = 0; state < states; state++) {
            intoFrom[state + 1] += intoFrom[state];
        }
        int[] placed = Arrays.copyOf(intoFrom, states);
        int[] into = new int[intoFrom[states]];
        for (int state = 0; state < states; state++) {
            for (int leads : to[state]) {
                into[placed[leads]++] = state;
            }
        }
        boolean[] out = accepting.clone();
        int[] pending = new int[states];
        int top = 0;
        for (int state = 0; state < states; state++) {
            if (out[state]) {
                pending[top++] = state;
            }
        }
        while (top > 0) {
            int state = pending[--top];
            for (int at = intoFrom[state]; at < intoFrom[state + 1]; at++) {
                if (!out[into[at]]) {
                    out[into[at]] = true;
                    pending[top++] = into[at];
                }
            }
        }
        return out;
    }

    /** The first of {@code ascending} at {@code value} or past it, which there is. */
    private static int firstFrom(int[] ascending, int value) {
        int low = 0;
        int high = ascending.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ascending[mid] < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
