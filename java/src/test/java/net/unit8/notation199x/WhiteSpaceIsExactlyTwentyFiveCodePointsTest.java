package net.unit8.notation199x;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code White_Space} is a closed set of twenty-five code points, so membership in it is checked
 * exactly rather than sampled: a range boundary {@link WhiteSpace#contains} got wrong (dropping
 * U+0085, mistyping U+202F, or widening U+2000-U+200A past its edges) would pass a test built from
 * a handful of code points, and does not pass this one.
 */
class WhiteSpaceIsExactlyTwentyFiveCodePointsTest {

    /** The 25 code points, written out rather than generated. */
    private static final int[] MEMBERS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
        0x0020,
        0x0085,
        0x00A0,
        0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029,
        0x202F,
        0x205F,
        0x3000,
    };

    /** Code points a boundary typo would most plausibly let through: the edges of every named
     *  range, one step outside each end, plus characters that look like white space and are not. */
    private static final int[] NON_MEMBERS = {
        0x0008, 0x000E,             // one below/above the TAB..CR run
        0x001C, 0x0007,             // C0 controls the old String.trim wrongly crossed
        0x001F, 0x0021,             // one below/above SPACE
        0x0084, 0x0086,             // one below/above NEL
        0x009F, 0x00A1,             // one below/above NBSP
        0x167F, 0x1681,             // one below/above OGHAM SPACE MARK
        0x1FFF, 0x200B,             // one below the 2000..200A run, and ZERO WIDTH SPACE just above it
        0x2027, 0x202A,             // one below LINE SEPARATOR, and one above PARAGRAPH SEPARATOR
        0x202E, 0x2030,             // one below/above NARROW NBSP
        0x205E, 0x2060,             // one below MEDIUM MATHEMATICAL SPACE, and WORD JOINER above it
        0x2FFF, 0x3001,             // one below/above IDEOGRAPHIC SPACE
        0xFEFF,                     // byte-order mark
    };

    @ParameterizedTest
    @MethodSource("members")
    void aMemberOfTheSetIsWhiteSpace(int cp) {
        assertTrue(WhiteSpace.contains(cp), "U+%04X".formatted(cp));
    }

    @ParameterizedTest
    @MethodSource("nonMembers")
    void aCodePointBesideTheSetIsNot(int cp) {
        assertFalse(WhiteSpace.contains(cp), "U+%04X".formatted(cp));
    }

    @Test
    void theSetIsThePropertyAsTheDatabaseStatesIt() throws IOException {
        Set<Integer> stated = new TreeSet<>();
        for (String line : Files.readAllLines(Ucd.file("PropList.txt"))) {
            String data = line.replaceFirst("#.*", "").trim();
            if (!data.endsWith("; White_Space")) {
                continue;
            }
            String range = data.substring(0, data.indexOf(';')).trim();
            int dots = range.indexOf("..");
            int from = Integer.parseInt(dots < 0 ? range : range.substring(0, dots), 16);
            int to = dots < 0 ? from : Integer.parseInt(range.substring(dots + 2), 16);
            IntStream.rangeClosed(from, to).forEach(stated::add);
        }
        Set<Integer> held = new TreeSet<>();
        IntStream.rangeClosed(0, Character.MAX_CODE_POINT).filter(WhiteSpace::contains).forEach(held::add);
        assertEquals(stated, held);
    }

    private static IntStream members() {
        return IntStream.of(MEMBERS);
    }

    private static IntStream nonMembers() {
        return IntStream.of(NON_MEMBERS);
    }
}
