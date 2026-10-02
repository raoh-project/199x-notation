package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TemporalText} answers every line of {@code suite/temporal.txt}. */
class TemporalTextAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyTextIsAdmittedOrRefusedForWhatItIs() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("temporal.txt", 3)) {
            TemporalText.Kind kind = TemporalText.Kind.valueOf(line.field(0));
            Optional<TemporalText.Refusal> expected = line.field(2).equals("ADMITTED")
                    ? Optional.empty() : Optional.of(TemporalText.Refusal.valueOf(line.field(2)));
            Optional<TemporalText.Refusal> answered = TemporalText.refusal(kind, line.text(1));
            if (!answered.equals(expected)) {
                wrong.add(line.where() + ": " + kind + " " + Suite.shown(line.text(1)) + " is "
                        + answered.map(Enum::name).orElse("ADMITTED") + ", not " + line.field(2));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
