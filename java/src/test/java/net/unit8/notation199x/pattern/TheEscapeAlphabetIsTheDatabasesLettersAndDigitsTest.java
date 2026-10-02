package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Ucd;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The characters a pattern keeps a backslash before are the letters and the decimal digits
 * {@code DerivedGeneralCategory.txt} states, every one of them and no other.
 */
class TheEscapeAlphabetIsTheDatabasesLettersAndDigitsTest {

    private static final Set<String> KEPT = Set.of("Lu", "Ll", "Lt", "Lm", "Lo", "Nd");

    @Test
    void theAlphabetIsTheCategoriesAsTheDatabaseStatesThem() throws IOException {
        Set<Integer> stated = new TreeSet<>();
        for (String line : Files.readAllLines(
                Ucd.file("extracted/DerivedGeneralCategory.txt"), StandardCharsets.UTF_8)) {
            String data = line.replaceFirst("#.*", "").trim();
            if (!data.contains(";") || !KEPT.contains(data.substring(data.indexOf(';') + 1).trim())) {
                continue;
            }
            String range = data.substring(0, data.indexOf(';')).trim();
            int dots = range.indexOf("..");
            int from = Integer.parseInt(dots < 0 ? range : range.substring(0, dots), 16);
            int to = dots < 0 ? from : Integer.parseInt(range.substring(dots + 2), 16);
            IntStream.rangeClosed(from, to).forEach(stated::add);
        }
        Set<Integer> held = new TreeSet<>();
        IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
                .filter(PatternAlphabet::isKeptAfterABackslash).forEach(held::add);
        assertEquals(stated, held);
        assertEquals(Ucd.VERSION, PatternAlphabet.unicodeVersion());
    }
}
