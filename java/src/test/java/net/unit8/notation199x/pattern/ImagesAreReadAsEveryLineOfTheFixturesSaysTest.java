package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An image of P1 is read, and text that is not one refused, as every line of {@code image/p1.txt}
 * says, and an image that is read accepts what the line says.
 */
class ImagesAreReadAsEveryLineOfTheFixturesSaysTest {

    @Test
    void everyImageIsReadOrRefusedAndAcceptsAsTheLineSays() {
        assertEquals(List.of(), Suite.wrong(Path.of("..", "image", "p1.txt"), 3, line -> {
            String image = line.asWritten(0);
            String outcome = line.oneOf(2, "ACCEPTED", "NOT_ACCEPTED", "REFUSED");
            StringPattern read;
            try {
                read = StringPattern.of(List.of(image));
            } catch (IllegalArgumentException refused) {
                line.empty(1);
                return outcome.equals("REFUSED") ? null : image + " is refused: " + refused.getMessage();
            }
            if (outcome.equals("REFUSED")) {
                line.empty(1);
                return image + " is read";
            }
            String subject = line.text(1);
            return read.matches(subject) == outcome.equals("ACCEPTED") ? null
                    : image + (outcome.equals("ACCEPTED") ? " refuses " : " accepts ") + Suite.shown(subject);
        }));
    }
}
