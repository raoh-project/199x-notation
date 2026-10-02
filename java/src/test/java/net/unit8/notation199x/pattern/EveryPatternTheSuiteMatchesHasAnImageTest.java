package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every pattern of {@code suite/pattern-match.txt} has an image here, and the images are written to
 * {@code target/images/pattern-match.txt} for an implementation that reads P1 to read.
 *
 * <p>What the other implementation is held to is the suite: it reads each image and answers what
 * the suite's lines say the pattern accepts. Neither the images' text nor this implementation's
 * matcher is what it is held to, so the file is written by every run and never checked in.
 */
class EveryPatternTheSuiteMatchesHasAnImageTest {

    @Test
    void everyPatternIsWrittenAsAnImage() throws IOException {
        Map<String, String> images = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>(Suite.wrong("pattern-match.txt", 3, line -> {
            String pattern = line.text(0);
            line.text(1);
            line.yesOrNo(2);
            if (images.containsKey(pattern)) {
                return null;
            }
            if (!(PatternParser.read(pattern) instanceof PatternRead.Read read)) {
                return Suite.shown(pattern) + " is not read";
            }
            if (!(PatternMachine.of(read.meaning()).image() instanceof PatternImage.Written written)) {
                return Suite.shown(pattern) + " has no image";
            }
            images.put(pattern, String.join("", written.strings()));
            return null;
        }));
        assertEquals(List.of(), wrong);

        StringBuilder out = new StringBuilder();
        out.append("# The image this implementation writes of each pattern of suite/pattern-match.txt.\n");
        out.append("#\n");
        out.append("# pattern ; image\n");
        images.forEach((pattern, image) -> out.append(pattern.codePoints()
                .mapToObj(cp -> String.format("%04X", cp)).collect(Collectors.joining(" ")))
                .append(" ; ").append(image).append('\n'));
        Path file = Path.of("target", "images", "pattern-match.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }
}
