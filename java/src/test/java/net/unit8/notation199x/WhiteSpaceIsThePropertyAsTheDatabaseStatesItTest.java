package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WhiteSpace} is {@code White_Space} as {@code PropList.txt} states it, over every code
 * point. The set every implementation is held to is listed in {@code suite/white-space.txt}; this
 * holds the generated table to the database it was generated from.
 */
class WhiteSpaceIsThePropertyAsTheDatabaseStatesItTest {

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
}
