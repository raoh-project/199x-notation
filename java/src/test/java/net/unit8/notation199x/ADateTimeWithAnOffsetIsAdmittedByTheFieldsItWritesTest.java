package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.unit8.notation199x.TemporalText.Kind;
import net.unit8.notation199x.TemporalText.Refusal;

/**
 * A date-time with an offset is a local date-time beside a displacement, admitted by the fields it
 * writes: a date there is, a time a clock shows, and an offset no further than eighteen hours.
 *
 * <p>Held against {@code java.time}'s factories over the same fields, which is what says a value
 * of the matching type can be built, and against what {@link OffsetDateTime#toString} writes. And
 * the ranges the rule states as numbers are the ones {@code java.time} has.
 */
class ADateTimeWithAnOffsetIsAdmittedByTheFieldsItWritesTest {

    @Test
    void theRangesAreTheOnesJavaTimeHas() {
        assertEquals(Year.MIN_VALUE, TemporalText.YEAR_MIN);
        assertEquals(Year.MAX_VALUE, TemporalText.YEAR_MAX);
        assertEquals(Instant.MIN.getEpochSecond(), TemporalText.INSTANT_MIN);
        assertEquals(Instant.MAX.getEpochSecond(), TemporalText.INSTANT_MAX);
    }

    @Test
    void whatIsAdmittedIsWhatTheFactoriesBuild() {
        Random random = new Random(199);
        int admitted = 0;
        for (int i = 0; i < 200_000; i++) {
            int year = random.nextInt(4) == 0 ? random.nextInt(-20_000, 20_000) : random.nextInt(1900, 2100);
            int month = random.nextInt(0, 14);
            int day = random.nextInt(0, 33);
            int hour = random.nextInt(0, 26);
            int minute = random.nextInt(0, 62);
            boolean withSecond = random.nextBoolean();
            int second = random.nextInt(0, 62);
            boolean negative = random.nextBoolean();
            int offsetHour = random.nextInt(0, 20);
            int offsetMinute = random.nextInt(0, 62);
            boolean withOffsetSecond = random.nextInt(4) == 0;
            int offsetSecond = random.nextInt(0, 62);
            String text = "%s-%02d-%02dT%02d:%02d%s%s%02d:%02d%s".formatted(
                    year(year), month, day, hour, minute,
                    withSecond ? ":%02d".formatted(second) : "",
                    negative ? "-" : "+", offsetHour, offsetMinute,
                    withOffsetSecond ? ":%02d".formatted(offsetSecond) : "");
            int sign = negative ? -1 : 1;
            boolean built = builds(() -> LocalDate.of(year, month, day))
                    && builds(() -> LocalTime.of(hour, minute, withSecond ? second : 0))
                    && builds(() -> ZoneOffset.ofHoursMinutesSeconds(sign * offsetHour,
                            sign * offsetMinute, sign * (withOffsetSecond ? offsetSecond : 0)));
            assertEquals(built, TemporalText.refusal(Kind.OFFSET_DATETIME, text).isEmpty(), text);
            admitted += built ? 1 : 0;
        }
        assertEquals(true, admitted > 1_000, "the walk reached texts that are admitted: " + admitted);
    }

    @Test
    void whatAnOffsetDateTimeWritesIsAdmitted() {
        Random random = new Random(199);
        for (int i = 0; i < 20_000; i++) {
            LocalDateTime local = LocalDateTime.of(
                    LocalDate.ofEpochDay(random.nextLong(-5_000_000, 5_000_000)),
                    LocalTime.ofNanoOfDay(random.nextBoolean()
                            ? random.nextLong(0, 86_400) * 1_000_000_000L
                            : random.nextLong(0, 86_400L * 1_000_000_000L)));
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(random.nextInt(-18 * 3600, 18 * 3600 + 1));
            String text = OffsetDateTime.of(local, offset).toString();
            assertEquals(Optional.empty(), TemporalText.refusal(Kind.OFFSET_DATETIME, text), text);
        }
        for (OffsetDateTime edge : new OffsetDateTime[] {OffsetDateTime.MIN, OffsetDateTime.MAX}) {
            assertEquals(Optional.empty(), TemporalText.refusal(Kind.OFFSET_DATETIME, edge.toString()));
        }
    }

    @Test
    void itIsNotAnInstantWrittenAnotherWay() {
        // The seconds may be left out, where an instant writes them.
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.OFFSET_DATETIME, "2026-09-30T12:34+09:00"));
        assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.INSTANT, "2026-09-30T12:34+09:00"));
        // Hour 24 is the start of the next day only in an instant.
        assertEquals(Optional.of(Refusal.MALFORMED),
                TemporalText.refusal(Kind.OFFSET_DATETIME, "2026-09-30T24:00:00Z"));
        // Second 60 is no second a clock shows, and is not told apart as a leap second here.
        assertEquals(Optional.of(Refusal.MALFORMED),
                TemporalText.refusal(Kind.OFFSET_DATETIME, "2016-12-31T23:59:60Z"));
        // The last year of a date is admitted whatever the offset, and is past every instant.
        assertEquals(Optional.empty(),
                TemporalText.refusal(Kind.OFFSET_DATETIME, "+999999999-12-31T23:59:59-18:00"));
        assertEquals(Optional.of(Refusal.MALFORMED),
                TemporalText.refusal(Kind.OFFSET_DATETIME, "+1000000000-01-01T00:00:00Z"));
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.INSTANT, "+1000000000-01-01T00:00:00Z"));
        // An offset is written, in hours and minutes at least, and Z is upper case.
        for (String text : new String[] {"2026-09-30T12:34:56", "2026-09-30T12:34:56+09", "2026-09-30T12:34:56+0900",
                "2026-09-30T12:34:56z", "2026-09-30t12:34:56Z", "2026-09-30T12:34:56+18:00:01"}) {
            assertEquals(Optional.of(Refusal.MALFORMED), TemporalText.refusal(Kind.OFFSET_DATETIME, text), text);
        }
        assertEquals(Optional.empty(), TemporalText.refusal(Kind.OFFSET_DATETIME, "2026-09-30T12:34:56.5-00:00"));
    }

    /** A year as the forms write it: four digits unsigned from 0000 to 9999, signed past them. */
    private static String year(int year) {
        if (year >= 0 && year <= 9999) {
            return "%04d".formatted(year);
        }
        return year < 0 ? "-%04d".formatted(-year) : "+" + year;
    }

    private static boolean builds(Runnable factory) {
        try {
            factory.run();
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
