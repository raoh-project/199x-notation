package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TemporalText} answers every line of {@code suite/temporal.txt}: whether a text is admitted,
 * and the reason it is refused where the line names one.
 */
class TemporalTextAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyTextIsAdmittedOrRefusedAsTheLineSays() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("temporal.txt", 4)) {
            TemporalText.Kind kind = TemporalText.Kind.valueOf(line.field(0));
            Optional<TemporalText.Refusal> answered = TemporalText.refusal(kind, line.text(1));
            boolean asSaid = switch (line.field(2)) {
                case "ADMITTED" -> answered.isEmpty();
                case "REFUSED" -> answered.isPresent()
                        && (line.field(3).isEmpty() || answered.get().name().equals(line.field(3)));
                default -> throw new IllegalStateException(line.where() + ": no outcome " + line.field(2));
            };
            if (!asSaid) {
                wrong.add(line.where() + ": " + kind + " " + Suite.shown(line.text(1)) + " is "
                        + answered.map(refusal -> "REFUSED " + refusal).orElse("ADMITTED") + ", not "
                        + line.field(2) + " " + line.field(3));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
