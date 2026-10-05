package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TemporalText} answers every line of {@code suite/temporal.txt}: whether a text is admitted
 * or refused. Which refusal it answers is Java's, and is tested in
 * {@link ATemporalTextIsRefusedForWhatItIsTest}.
 */
class TemporalTextAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyTextIsAdmittedOrRefusedAsTheLineSays() {
        assertEquals(List.of(), Suite.wrong("temporal.txt", 3, line -> {
            TemporalText.Kind kind = TemporalText.Kind.valueOf(
                    line.oneOf(0, "DATE", "TIME", "DATETIME", "OFFSET_DATETIME", "INSTANT"));
            String text = line.text(1);
            boolean admitted = line.oneOf(2, "ADMITTED", "REFUSED").equals("ADMITTED");
            Optional<TemporalText.Refusal> answered = TemporalText.refusal(kind, text);
            return answered.isEmpty() == admitted ? null
                    : kind + " " + Suite.shown(text) + " is " + answered.map(Enum::name).orElse("ADMITTED");
        }));
    }
}
