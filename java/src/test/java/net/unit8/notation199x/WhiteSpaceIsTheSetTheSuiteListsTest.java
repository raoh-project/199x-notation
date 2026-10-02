package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WhiteSpace} holds the scalar values {@code suite/white-space.txt} lists and no other,
 * asked of every scalar value.
 */
class WhiteSpaceIsTheSetTheSuiteListsTest {

    @Test
    void everyScalarValueIsAnsweredAsTheSuiteLists() {
        Set<Integer> listed = new TreeSet<>();
        assertEquals(List.of(), Suite.wrong("white-space.txt", 1, line -> {
            int listedHere = line.scalar(0);
            return listed.add(listedHere) ? null : "U+%04X is listed twice".formatted(listedHere);
        }));
        List<String> wrong = new ArrayList<>();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            if (WhiteSpace.contains(cp) != listed.contains(cp)) {
                wrong.add("U+%04X".formatted(cp));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
