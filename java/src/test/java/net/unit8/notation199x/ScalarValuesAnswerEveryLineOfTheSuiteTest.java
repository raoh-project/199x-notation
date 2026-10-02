package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScalarValues} answers every line of {@code suite/scalar-length.txt} and
 * {@code suite/scalar-order.txt}.
 */
class ScalarValuesAnswerEveryLineOfTheSuiteTest {

    @Test
    void everyLengthIsCountedInScalarValues() {
        assertEquals(List.of(), Suite.wrong("scalar-length.txt", 2, line -> {
            String text = line.text(0);
            long expected = line.number(1);
            long counted = ScalarValues.count(text);
            return counted == expected ? null : Suite.shown(text) + " is counted " + counted + ", not " + expected;
        }));
    }

    @Test
    void everyOrderIsTheScalarValuesOneAfterAnother() {
        assertEquals(List.of(), Suite.wrong("scalar-order.txt", 3, line -> {
            String a = line.text(0);
            String b = line.text(1);
            int expected = switch (line.oneOf(2, "LESS", "EQUAL", "GREATER")) {
                case "LESS" -> -1;
                case "EQUAL" -> 0;
                default -> 1;
            };
            int answered = Integer.signum(ScalarValues.compare(a, b));
            return answered == expected ? null
                    : Suite.shown(a) + " against " + Suite.shown(b) + " is " + answered + ", not " + expected;
        }));
    }
}
