package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScalarValues} answers every line of {@code suite/scalar-length.txt} and
 * {@code suite/scalar-order.txt}.
 */
class ScalarValuesAnswerEveryLineOfTheSuiteTest {

    @Test
    void everyLengthIsCountedInScalarValues() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("scalar-length.txt", 2)) {
            long counted = ScalarValues.count(line.text(0));
            if (counted != Long.parseLong(line.field(1))) {
                wrong.add(line.where() + ": " + Suite.shown(line.text(0)) + " is counted " + counted
                        + ", not " + line.field(1));
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void everyOrderIsTheScalarValuesOneAfterAnother() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("scalar-order.txt", 3)) {
            int expected = switch (line.field(2)) {
                case "LESS" -> -1;
                case "EQUAL" -> 0;
                case "GREATER" -> 1;
                default -> throw new IllegalStateException(line.where() + ": no order " + line.field(2));
            };
            int answered = Integer.signum(ScalarValues.compare(line.text(0), line.text(1)));
            if (answered != expected) {
                wrong.add(line.where() + ": " + Suite.shown(line.text(0)) + " against "
                        + Suite.shown(line.text(1)) + " is " + answered + ", not " + line.field(2));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
