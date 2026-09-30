package net.unit8.notation199x;

import java.time.Instant;
import java.time.Year;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which text names a date, a time, a date-time or an instant.
 *
 * <p>Each form is a regular expression over ASCII, and that expression alone decides whether a text
 * is in the form. Whether the fields then name a day and a moment that exist is arithmetic on the
 * fields, done here, so nothing in {@code java.time} decides what is a temporal: its parsers take
 * lower-case {@code t} and {@code z} and a decimal point with no digits after it, and a library
 * built on them would read a different language from one release to the next. A value is built by
 * whoever holds the text once it is admitted, and what it builds is what this said exists.
 *
 * <p>Each temporal is admitted in its own domain. A date and a date-time hold the
 * years {@link Year} does, and an instant holds the moments {@link Instant} does, which
 * reach a year further on either side of them and are counted in epoch seconds after the offset and
 * an hour 24 have been applied. Borrowing one domain's range for another puts the end of a range
 * where a value can be written that an encoder then cannot write back.
 *
 * <p>A time and a date-time may carry a fraction of a second, as an instant may. A caller whose
 * type is held to the second refuses the fraction itself: the forms are ASCII, so a text that was
 * admitted carries one exactly where it holds a full stop. An offset is a spelling and not a zone:
 * it is a displacement from UTC that names the same moment as the {@code Z} form of it.
 *
 * <p>The refusal is the reason and not a flag, because the reasons are different things to tell a
 * caller: text that is no temporal, and text that names a leap second.
 */
public final class TemporalText {

    /** The four temporals that have a text form. */
    public enum Kind { DATE, TIME, DATETIME, INSTANT }

    /** Why a text is not one. */
    public enum Refusal {
        /** Not in the form, or in it and naming no day or moment. */
        MALFORMED,
        /** An instant whose second is 60. */
        LEAP_SECOND
    }

    // Four digits and no sign for 0000 to 9999; otherwise a sign, and no leading zero beyond the
    // four-digit minimum. "-0000" is not year 0. Ten digits reach the years an Instant holds.
    private static final String YEAR =
            "(?<year>[0-9]{4}|\\+[1-9][0-9]{4,9}|-(?!0000)[0-9]{4}|-[1-9][0-9]{4,9})";
    private static final String DATE = YEAR + "-(?<month>[0-9]{2})-(?<day>[0-9]{2})";
    private static final String HOUR_MINUTE = "(?<hour>[0-9]{2}):(?<minute>[0-9]{2})";
    // A fraction has one to nine digits. A point with none after it is not a fraction.
    private static final String SECOND = ":(?<second>[0-9]{2})(?:\\.(?<fraction>[0-9]{1,9}))?";
    private static final String TIME = HOUR_MINUTE + "(?:" + SECOND + ")?";
    private static final String OFFSET = "(?:(?<utc>Z)|(?<offsetSign>[+-])"
            + "(?<offsetHour>[0-9]{2}):(?<offsetMinute>[0-9]{2})(?::(?<offsetSecond>[0-9]{2}))?)";

    private static final Pattern DATE_FORM = Pattern.compile(DATE);
    private static final Pattern TIME_FORM = Pattern.compile(TIME);
    private static final Pattern DATETIME_FORM = Pattern.compile(DATE + "T" + TIME);
    // An instant requires its seconds.
    private static final Pattern INSTANT_FORM =
            Pattern.compile(DATE + "T" + HOUR_MINUTE + SECOND + OFFSET);

    private static final long SECONDS_PER_DAY = 86_400L;
    /** The furthest an offset reaches from UTC, eighteen hours. */
    private static final int MAX_OFFSET_SECONDS = 18 * 3600;
    /** Days from 0000-03-01 to 1970-01-01, which the day count below is taken from. */
    private static final long DAYS_TO_EPOCH = 719_468L;

    private TemporalText() {}

    /** Why {@code text} is not a {@code kind}, or empty where it is one. */
    public static Optional<Refusal> refusal(Kind kind, String text) {
        return switch (kind) {
            case DATE -> {
                Matcher m = DATE_FORM.matcher(text);
                yield m.matches() && dateExists(m) ? none() : malformed();
            }
            case TIME -> local(TIME_FORM.matcher(text), false);
            case DATETIME -> local(DATETIME_FORM.matcher(text), true);
            case INSTANT -> instant(INSTANT_FORM.matcher(text));
        };
    }

    private static Optional<Refusal> local(Matcher m, boolean withDate) {
        return m.matches() && (!withDate || dateExists(m)) && timeExists(m) ? none() : malformed();
    }

    private static Optional<Refusal> instant(Matcher m) {
        if (!m.matches() || !offsetExists(m)) {
            return malformed();
        }
        int second = Integer.parseInt(m.group("second"));
        if (second == 60) {
            // A second that does not exist. Whether the moment it is said at does is asked with the
            // second every minute has.
            return momentExists(m, 59) ? Optional.of(Refusal.LEAP_SECOND) : malformed();
        }
        return momentExists(m, second) ? none() : malformed();
    }

    /** Whether the date is one a {@code LocalDate} holds: a year within {@link Year}'s, and a day
     *  the month has. */
    private static boolean dateExists(Matcher m) {
        long year = Long.parseLong(m.group("year"));
        return year >= Year.MIN_VALUE && year <= Year.MAX_VALUE && dayExists(m, year);
    }

    private static boolean dayExists(Matcher m, long year) {
        int month = Integer.parseInt(m.group("month"));
        int day = Integer.parseInt(m.group("day"));
        return month >= 1 && month <= 12 && day >= 1 && day <= lengthOfMonth(year, month);
    }

    private static int lengthOfMonth(long year, int month) {
        return switch (month) {
            case 2 -> Year.isLeap(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    /** Whether the time of day is one a {@code LocalTime} holds. Hour 24 is not, whatever follows. */
    private static boolean timeExists(Matcher m) {
        String second = m.group("second");
        return Integer.parseInt(m.group("hour")) <= 23
                && Integer.parseInt(m.group("minute")) <= 59
                && (second == null || Integer.parseInt(second) <= 59);
    }

    /**
     * Whether the moment the text names is one an {@code Instant} holds, with {@code second} as its
     * second.
     *
     * <p>Counted in epoch seconds after the offset is taken off, so a year at either end of the
     * range and an hour 24 that carries into the next day or an offset that carries into the last
     * are asked of the moment they name and not of the fields they were written in. {@code 24:00:00}
     * is the start of the next day, and only when nothing follows it: an hour 24 with minutes or a
     * fraction names no moment.
     */
    private static boolean momentExists(Matcher m, int second) {
        long year = Long.parseLong(m.group("year"));
        int hour = Integer.parseInt(m.group("hour"));
        int minute = Integer.parseInt(m.group("minute"));
        boolean timeExists = hour == 24
                ? minute == 0 && second == 0 && m.group("fraction") == null
                : hour <= 23 && minute <= 59 && second <= 59;
        if (!timeExists || !dayExists(m, year)) {
            return false;
        }
        long epochSecond = epochDay(year, Integer.parseInt(m.group("month")),
                Integer.parseInt(m.group("day"))) * SECONDS_PER_DAY
                + hour * 3600L + minute * 60L + second - offsetSeconds(m);
        return epochSecond >= Instant.MIN.getEpochSecond() && epochSecond <= Instant.MAX.getEpochSecond();
    }

    /** Days from 1970-01-01 to a day of the proleptic Gregorian calendar, which the caller has
     *  established exists. The year runs from March, so the leap day is the last of it. */
    private static long epochDay(long year, int month, int day) {
        long shifted = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(shifted, 400L);
        long yearOfEra = shifted - era * 400L;
        long dayOfYear = (153L * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146_097L + dayOfEra - DAYS_TO_EPOCH;
    }

    /** Whether the offset is one that reaches no further than eighteen hours, with minutes and
     *  seconds that are those of a clock. */
    private static boolean offsetExists(Matcher m) {
        if (m.group("utc") != null) {
            return true;
        }
        String second = m.group("offsetSecond");
        return Integer.parseInt(m.group("offsetMinute")) <= 59
                && (second == null || Integer.parseInt(second) <= 59)
                && Math.abs(offsetSeconds(m)) <= MAX_OFFSET_SECONDS;
    }

    /** The displacement from UTC the offset writes, in seconds; nought for {@code Z}. */
    private static int offsetSeconds(Matcher m) {
        if (m.group("utc") != null) {
            return 0;
        }
        String second = m.group("offsetSecond");
        int magnitude = Integer.parseInt(m.group("offsetHour")) * 3600
                + Integer.parseInt(m.group("offsetMinute")) * 60
                + (second == null ? 0 : Integer.parseInt(second));
        return "-".equals(m.group("offsetSign")) ? -magnitude : magnitude;
    }

    private static Optional<Refusal> none() {
        return Optional.empty();
    }

    private static Optional<Refusal> malformed() {
        return Optional.of(Refusal.MALFORMED);
    }
}
