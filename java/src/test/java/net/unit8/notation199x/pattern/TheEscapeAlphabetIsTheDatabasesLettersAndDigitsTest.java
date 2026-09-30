package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Ucd;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code pattern-escape-alphabet.txt} is an excerpt of {@code DerivedGeneralCategory.txt}, and the
 * excerpt is the file's: its header, and every line of the values it was cut to hold.
 */
class TheEscapeAlphabetIsTheDatabasesLettersAndDigitsTest {

    private static final Set<String> KEPT = Set.of("Lu", "Ll", "Lt", "Lm", "Lo", "Nd");

    @Test
    void theExcerptHoldsEveryLineOfItsValuesAndNoOther() throws IOException {
        List<String> whole = Files.readAllLines(
                Ucd.file("extracted/DerivedGeneralCategory.txt"), StandardCharsets.UTF_8);
        List<String> excerpt;
        try (var in = PatternAlphabet.class.getResourceAsStream("pattern-escape-alphabet.txt")) {
            excerpt = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertEquals(whole.get(0), excerpt.get(0));
        assertEquals(Ucd.VERSION, PatternAlphabet.unicodeVersion());
        assertEquals(dataLines(whole), dataLines(excerpt));
    }

    private static List<String> dataLines(List<String> lines) {
        return lines.stream()
                .map(line -> line.replaceFirst("#.*", "").trim())
                .filter(line -> line.contains(";") && KEPT.contains(line.substring(line.indexOf(';') + 1).trim()))
                .map(line -> line.replaceAll("\\s+", ""))
                .toList();
    }
}
