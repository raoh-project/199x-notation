import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * A value for every code point from U+0000 to U+10FFFF, held so that one is found in two steps
 * whatever the code point: the code space is cut into blocks of {@link #PAGE} code points, the first
 * stage gives each block the page that holds its values, and a page is held once however many blocks
 * hold the same values. Most blocks hold nothing but the value most code points have, and share one
 * page.
 *
 * <p>This is how a table is laid out, not what it says: a model works out the value of each code
 * point, and an emitter writes the two stages in its language. Nothing here is about Unicode.
 *
 * @param blocks the page of each block, by the block's number, {@code cp >>> SHIFT}
 * @param pages  the pages, each {@link #PAGE} values long, the one at {@code cp & (PAGE - 1)} the
 *               value of {@code cp}
 */
record PagedTable(int[] blocks, List<int[]> pages) {

    /** How many bits of a code point pick a value within a page. */
    static final int SHIFT = 8;

    /** How many code points a page holds. */
    static final int PAGE = 1 << SHIFT;

    /** How many blocks the code space is cut into. */
    static final int BLOCKS = (Character.MAX_CODE_POINT + 1) >>> SHIFT;

    /**
     * The table of {@code value}, asked once for every code point.
     *
     * @param value the value of a code point
     * @return the table, which answers {@code value} for every code point
     */
    static PagedTable of(IntUnaryOperator value) {
        int[] blocks = new int[BLOCKS];
        List<int[]> pages = new ArrayList<>();
        Map<List<Integer>, Integer> numbered = new HashMap<>();
        for (int block = 0; block < BLOCKS; block++) {
            int[] page = new int[PAGE];
            for (int i = 0; i < PAGE; i++) {
                page[i] = value.applyAsInt(block << SHIFT | i);
            }
            List<Integer> key = Arrays.stream(page).boxed().toList();
            Integer number = numbered.get(key);
            if (number == null) {
                number = pages.size();
                numbered.put(key, number);
                pages.add(page);
            }
            blocks[block] = number;
        }
        PagedTable table = new PagedTable(blocks, List.copyOf(pages));
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (table.get(cp) != value.applyAsInt(cp)) {
                throw new IllegalStateException("the table of U+" + UcdModel.hex(cp) + " is not its value");
            }
        }
        return table;
    }

    /** The value of {@code cp}. */
    int get(int cp) {
        return pages.get(blocks[cp >>> SHIFT])[cp & (PAGE - 1)];
    }

    /** The pages one after another, as one array: the value of {@code cp} is at
     *  {@code blocks[cp >>> SHIFT] << SHIFT | cp & (PAGE - 1)}. */
    int[] values() {
        int[] values = new int[pages.size() * PAGE];
        for (int i = 0; i < pages.size(); i++) {
            System.arraycopy(pages.get(i), 0, values, i * PAGE, PAGE);
        }
        return values;
    }

    /** The greatest value held, so that an emitter can check what it writes each value in holds it. */
    int greatest() {
        return Arrays.stream(values()).max().orElse(0);
    }
}
