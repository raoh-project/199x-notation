package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.unit8.notation199x.TemporalText.Kind;
import net.unit8.notation199x.TemporalText.Refusal;

/**
 * The places the grammar of a temporal is a decision rather than a consequence: a fraction of a
 * second, hour 24, and second 60.
 *
 * <p>Whether every implementation admits the same texts is in {@code suite/temporal.txt}. Which
 * refusal a text gets, malformed or a leap second, the specifications give the same answer for, so
 * the refusal asserted here is Java's.
 */
class ATemporalTextIsRefusedForWhatItIsTest {

    @Test
    void aTimeAndADateTimeMayCarryAFractionOfOneToNineDigits() {
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.TIME, "12:34:56.5"));
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.TIME, "12:34:56.123456789"));
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.DATETIME, "2026-09-30T12:34:56.000"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.TIME, "12:34:56."));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.TIME, "12:34:56.1234567890"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.TIME, "12:34.5"));
    }

    @Test
    void hourTwentyFourIsTheStartOfTheNextDayOnlyInAnInstantAndOnlyWithNothingAfterIt() {
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.INSTANT, "2026-09-30T24:00:00Z"));
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.INSTANT, "2026-09-30T24:00:00+09:00"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.INSTANT, "2026-09-30T24:00:00.0Z"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.INSTANT, "2026-09-30T24:00:01Z"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.INSTANT, "2026-09-30T24:01:00Z"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.TIME, "24:00:00"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.DATETIME, "2026-09-30T24:00:00"));
    }

    @Test
    void secondSixtyIsALeapSecondWhereTheRestOfTheInstantExists() {
        assertEquals(Optional.of(Refusal.LEAP_SECOND), TemporalText.refusal(Kind.INSTANT, "2016-12-31T23:59:60Z"));
        assertEquals(Optional.of(Refusal.LEAP_SECOND), TemporalText.refusal(Kind.INSTANT, "2026-09-30T12:00:60.5Z"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.INSTANT, "2026-02-30T23:59:60Z"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.TIME, "23:59:60"));
    }
}
