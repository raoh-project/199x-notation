package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An image of each format is read, and text that is not one refused, as every line of the format's
 * fixtures, {@code image/p1.txt} and {@code image/p2.txt}, says, and an image that is read accepts
 * what the line says.
 */
class ImagesAreReadAsEveryLineOfTheFixturesSaysTest {

    @ParameterizedTest
    @ValueSource(strings = {"p1.txt", "p2.txt"})
    void everyImageIsReadOrRefusedAndAcceptsAsTheLineSays(String fixtures) {
        assertEquals(List.of(), Suite.wrong(Path.of("..", "image", fixtures), 3, line -> {
            String image = line.textAsShown(0);
            String outcome = line.oneOf(2, "ACCEPTED", "NOT_ACCEPTED", "REFUSED");
            StringPattern read;
            try {
                read = StringPattern.of(List.of(image));
            } catch (IllegalArgumentException refused) {
                line.empty(1);
                return outcome.equals("REFUSED") ? null : Suite.shown(image) + " is refused: " + refused.getMessage();
            }
            if (outcome.equals("REFUSED")) {
                line.empty(1);
                return Suite.shown(image) + " is read";
            }
            String subject = line.text(1);
            return read.matches(subject) == outcome.equals("ACCEPTED") ? null
                    : Suite.shown(image) + (outcome.equals("ACCEPTED") ? " refuses " : " accepts ") + Suite.shown(subject);
        }));
    }
}
