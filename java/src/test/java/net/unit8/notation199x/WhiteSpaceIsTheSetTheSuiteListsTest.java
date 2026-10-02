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
        for (Suite.Line line : Suite.read("white-space.txt", 1)) {
            listed.add(line.text(0).codePointAt(0));
        }
        List<String> wrong = new ArrayList<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
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
