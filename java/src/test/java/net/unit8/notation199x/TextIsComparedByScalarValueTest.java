package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScalarValues#compare} is the lexicographic order of the scalar values two strings are made
 * of.
 *
 * <p>The comparison is held against the definition itself: the code points taken out and compared
 * one after another. It does not take them out — it compares units and moves one range of them — so
 * what is checked is that the shortcut and the definition agree, over every string of up to two
 * characters drawn from either side of each place the units' order parts from the scalar values'.
 */
class TextIsComparedByScalarValueTest {

    private static final int[] CHARACTERS = {
            0, 'a', 0xD7FF, 0xE000, 0xFFE5, 0xFFFF, 0x10000, 0x103FF, 0x10400, 0x20BB7, 0x10FFFF};

    private static List<String> strings() {
        List<String> out = new ArrayList<>(List.of(""));
        for (int one : CHARACTERS) {
            out.add(new String(new int[] {one}, 0, 1));
            for (int two : CHARACTERS) {
                out.add(new String(new int[] {one, two}, 0, 2));
            }
        }
        return out;
    }

    @Test
    void theOrderIsTheScalarValuesOneAfterAnother() {
        List<String> strings = strings();
        for (String a : strings) {
            for (String b : strings) {
                assertEquals(Integer.signum(Arrays.compare(a.codePoints().toArray(),
                                b.codePoints().toArray())),
                        Integer.signum(ScalarValues.compare(a, b)),
                        a.codePoints().boxed().toList() + " against " + b.codePoints().boxed().toList());
            }
        }
    }

    @Test
    void halfAPairIsFoundWhereItStands() {
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        assertEquals(-1, ScalarValues.halfAPairAt(""));
        assertEquals(-1, ScalarValues.halfAPairAt("a" + high + low + "b"));
        assertEquals(0, ScalarValues.halfAPairAt(high));
        assertEquals(0, ScalarValues.halfAPairAt(low));
        assertEquals(0, ScalarValues.halfAPairAt(low + high));
        assertEquals(1, ScalarValues.halfAPairAt("a" + high));
        assertEquals(0, ScalarValues.halfAPairAt(high + "a"));
        assertEquals(2, ScalarValues.halfAPairAt(high + low + high));
        assertEquals(0, ScalarValues.halfAPairAt(high + high + low));
    }
}
