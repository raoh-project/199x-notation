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
