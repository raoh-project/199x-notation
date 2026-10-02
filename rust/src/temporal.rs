/// The temporals that have a text form.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TemporalKind {
    /// A day: `yyyy-MM-dd`.
    Date,
    /// A time of day: `HH:mm` or `HH:mm:ss`, with a fraction of a second or not.
    Time,
    /// A day and a time of day, joined by `T`.
    DateTime,
    /// A day and a time of day, and an offset from UTC: `Z` or `±HH:mm[:ss]`.
    OffsetDateTime,
    /// A moment: a day, a time of day with its seconds, and an offset from UTC.
    Instant,
}

/// Why a text is not a temporal of the kind it was to be.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TemporalRefusal {
    /// Not in the form, or in it and naming no day or moment.
    Malformed,
    /// An instant whose second is 60.
    LeapSecond,
}

/// The least year of a date.
pub const YEAR_MIN: i64 = -999_999_999;
/// The greatest year of a date.
pub const YEAR_MAX: i64 = 999_999_999;
/// The least moment of an instant, in seconds from 1970-01-01T00:00:00Z: the first second of year
/// -1000000000.
pub const INSTANT_MIN: i64 = -31_557_014_167_219_200;
/// The greatest moment of an instant, in seconds from 1970-01-01T00:00:00Z: the last second of
/// year 1000000000.
pub const INSTANT_MAX: i64 = 31_556_889_864_403_199;

const SECONDS_PER_DAY: i64 = 86_400;
/// The furthest an offset reaches from UTC, eighteen hours.
const MAX_OFFSET_SECONDS: i64 = 18 * 3600;
/// Days from 0000-03-01 to 1970-01-01, which the day count below is taken from.
const DAYS_TO_EPOCH: i64 = 719_468;

/// Whether `text` is a `kind`, and why not where it is not.
///
/// Each form is a grammar over ASCII, and that grammar alone decides whether a text is in the form.
/// Whether the fields then name a day and a moment that exist is arithmetic on the fields, done
/// here, so no date library decides what is a temporal.
///
/// A year is four digits and no sign for 0000 to 9999, and otherwise a sign and no leading zero
/// beyond the four-digit minimum; `-0000` is not year 0. A fraction of a second is one to nine
/// digits after the seconds. An offset is `Z`, or a sign, hours and minutes, and seconds or not.
///
/// Each temporal is admitted in its own domain. A date, a date-time and a date-time with an offset
/// hold the years from [`YEAR_MIN`] to [`YEAR_MAX`] in the fields they are written in, whatever the
/// offset. An instant holds the moments from [`INSTANT_MIN`] to [`INSTANT_MAX`], counted in epoch
/// seconds after the offset and an hour 24 have been applied.
///
/// A date-time with an offset and an instant are two forms and not one. The first is a local
/// date-time beside a displacement: its seconds may be left out, its hour is one a clock shows, and
/// it is admitted by the fields it writes. The second is a moment: its seconds are written,
/// `24:00:00` with nothing after it is the start of the next day, and it is admitted by the moment
/// it names. An instant whose second is 60 names a leap second, which is refused as one where the
/// moment it is said at, with second 59, is one an instant holds.
pub fn check_temporal(kind: TemporalKind, text: &str) -> Result<(), TemporalRefusal> {
    admit(kind, text).map(|_| ())
}

/// A date, as [`read_date`] reads it.
///
/// The readers are this crate's own, and not a rule the implementations share: the suite holds
/// which text is admitted and not what it names, and an implementation in a language with date
/// types builds its value from admitted text with them. Rust has none, so the readers give what
/// [`check_temporal`] read, for a caller to build its own value from rather than read the text
/// again. Each type is the fields that value needs and no more.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub struct TemporalDate {
    /// The year, from [`YEAR_MIN`] to [`YEAR_MAX`].
    pub year: i32,
    /// The month, from 1 to 12.
    pub month: u8,
    /// The day of the month, from 1 to the month's last.
    pub day: u8,
}

/// A time of day, as [`read_time`] reads it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub struct TemporalTime {
    /// The hour, from 0 to 23.
    pub hour: u8,
    /// The minute, from 0 to 59.
    pub minute: u8,
    /// The second, from 0 to 59, and 0 where it is left out.
    pub second: u8,
    /// The fraction of the second, in nanoseconds: its digits followed by zeros to nine.
    pub nanosecond: u32,
}

/// A date and a time of day, as [`read_date_time`] reads them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub struct TemporalDateTime {
    /// The date.
    pub date: TemporalDate,
    /// The time of day.
    pub time: TemporalTime,
}

/// A date and a time of day, and an offset from UTC, as [`read_offset_date_time`] reads them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub struct TemporalOffsetDateTime {
    /// The date and the time of day, as written.
    pub date_time: TemporalDateTime,
    /// The offset in seconds east of UTC, from -64800 to 64800: 0 for `Z`, `+00:00` and `-00:00`.
    pub offset_seconds: i32,
}

/// A moment, as [`read_instant`] reads it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[non_exhaustive]
pub struct TemporalInstant {
    /// The seconds from 1970-01-01T00:00:00Z to the second at or before the moment, from
    /// [`INSTANT_MIN`] to [`INSTANT_MAX`].
    pub epoch_second: i64,
    /// The nanoseconds from that second to the moment.
    pub nanosecond: u32,
}

/// The date `text` names, where [`check_temporal`] admits it as a [`TemporalKind::Date`].
pub fn read_date(text: &str) -> Result<TemporalDate, TemporalRefusal> {
    admit(TemporalKind::Date, text).map(|fields| fields.date())
}

/// The time of day `text` names, where [`check_temporal`] admits it as a [`TemporalKind::Time`].
pub fn read_time(text: &str) -> Result<TemporalTime, TemporalRefusal> {
    admit(TemporalKind::Time, text).map(|fields| fields.time())
}

/// The date and time of day `text` names, where [`check_temporal`] admits it as a
/// [`TemporalKind::DateTime`].
pub fn read_date_time(text: &str) -> Result<TemporalDateTime, TemporalRefusal> {
    admit(TemporalKind::DateTime, text).map(|fields| fields.date_time())
}

/// The date, time of day and offset `text` names, where [`check_temporal`] admits it as a
/// [`TemporalKind::OffsetDateTime`]. The date and time are the ones written, not moved by the
/// offset.
pub fn read_offset_date_time(text: &str) -> Result<TemporalOffsetDateTime, TemporalRefusal> {
    admit(TemporalKind::OffsetDateTime, text).map(|fields| TemporalOffsetDateTime {
        date_time: fields.date_time(),
        offset_seconds: fields.offset_seconds() as i32,
    })
}

/// The moment `text` names, where [`check_temporal`] admits it as a [`TemporalKind::Instant`]:
/// the epoch second admitting it worked out, with the offset applied and an hour 24 read as the
/// start of the next day.
pub fn read_instant(text: &str) -> Result<TemporalInstant, TemporalRefusal> {
    admit(TemporalKind::Instant, text).map(|fields| TemporalInstant {
        epoch_second: fields.epoch_second,
        nanosecond: fields.nanosecond,
    })
}

/// The fields of `text`, where it is a `kind`, and why not where it is not. This is the one reading
/// of the text: [`check_temporal`] and every reader answer from it.
fn admit(kind: TemporalKind, text: &str) -> Result<Fields, TemporalRefusal> {
    let mut cursor = Cursor {
        text: text.as_bytes(),
        at: 0,
    };
    let mut fields = cursor
        .read(kind)
        .filter(|_| cursor.at == text.len())
        .ok_or(TemporalRefusal::Malformed)?;
    let admitted = match kind {
        TemporalKind::Date => fields.date_exists(),
        TemporalKind::Time => fields.time_exists(),
        TemporalKind::DateTime => fields.date_exists() && fields.time_exists(),
        TemporalKind::OffsetDateTime => {
            fields.date_exists() && fields.time_exists() && fields.offset_exists()
        }
        TemporalKind::Instant => {
            if !fields.offset_exists() {
                return Err(TemporalRefusal::Malformed);
            }
            if fields.second == Some(60) {
                return if fields.epoch_second(59).is_some() {
                    Err(TemporalRefusal::LeapSecond)
                } else {
                    Err(TemporalRefusal::Malformed)
                };
            }
            match fields.epoch_second(fields.second.unwrap_or(0)) {
                Some(epoch_second) => {
                    fields.epoch_second = epoch_second;
                    true
                }
                None => false,
            }
        }
    };
    if admitted {
        Ok(fields)
    } else {
        Err(TemporalRefusal::Malformed)
    }
}

/// The fields a text in one of the forms writes, as numbers, before any is asked whether it exists.
#[derive(Default)]
struct Fields {
    year: i64,
    month: i64,
    day: i64,
    hour: i64,
    minute: i64,
    second: Option<i64>,
    fraction: bool,
    /// The fraction of the second, in nanoseconds.
    nanosecond: u32,
    /// The moment an instant names, in seconds from the epoch, once it has been admitted.
    epoch_second: i64,
    /// Seconds from UTC, as written, and whether its minutes and seconds are those of a clock.
    offset: Option<(i64, bool)>,
}

impl Fields {
    /// Whether the date is one there is: a year within the range, and a day the month has.
    fn date_exists(&self) -> bool {
        (YEAR_MIN..=YEAR_MAX).contains(&self.year) && self.day_exists()
    }

    fn day_exists(&self) -> bool {
        (1..=12).contains(&self.month)
            && self.day >= 1
            && self.day <= length_of_month(self.year, self.month)
    }

    /// Whether the time of day is one a clock shows. Hour 24 is not, whatever follows.
    fn time_exists(&self) -> bool {
        self.hour <= 23 && self.minute <= 59 && self.second.is_none_or(|second| second <= 59)
    }

    /// Whether the offset reaches no further than eighteen hours, with minutes and seconds that
    /// are those of a clock.
    fn offset_exists(&self) -> bool {
        self.offset
            .is_some_and(|(seconds, clock)| clock && seconds.abs() <= MAX_OFFSET_SECONDS)
    }

    /// The moment the text names, with `second` as its second, in seconds from the epoch, where it
    /// is one and within the range of an instant. `24:00:00` is the start of the next day, and only
    /// when nothing follows it.
    fn epoch_second(&self, second: i64) -> Option<i64> {
        let time_exists = if self.hour == 24 {
            self.minute == 0 && second == 0 && !self.fraction
        } else {
            self.hour <= 23 && self.minute <= 59 && second <= 59
        };
        if !time_exists || !self.day_exists() {
            return None;
        }
        let epoch_second = epoch_day(self.year, self.month, self.day) * SECONDS_PER_DAY
            + self.hour * 3600
            + self.minute * 60
            + second
            - self.offset_seconds();
        (INSTANT_MIN..=INSTANT_MAX)
            .contains(&epoch_second)
            .then_some(epoch_second)
    }

    /// The offset in seconds east of UTC, 0 where there is none.
    fn offset_seconds(&self) -> i64 {
        self.offset.map_or(0, |(seconds, _)| seconds)
    }

    // The fields below are read only of text that is admitted, whose fields are within the ranges
    // the types hold.

    fn date(&self) -> TemporalDate {
        TemporalDate {
            year: self.year as i32,
            month: self.month as u8,
            day: self.day as u8,
        }
    }

    fn time(&self) -> TemporalTime {
        TemporalTime {
            hour: self.hour as u8,
            minute: self.minute as u8,
            second: self.second.unwrap_or(0) as u8,
            nanosecond: self.nanosecond,
        }
    }

    fn date_time(&self) -> TemporalDateTime {
        TemporalDateTime {
            date: self.date(),
            time: self.time(),
        }
    }
}

fn length_of_month(year: i64, month: i64) -> i64 {
    match month {
        2 if is_leap(year) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Whether `year` of the proleptic Gregorian calendar has a 29 February.
fn is_leap(year: i64) -> bool {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

/// Days from 1970-01-01 to a day of the proleptic Gregorian calendar that exists. The year runs
/// from March, so the leap day is the last of it.
fn epoch_day(year: i64, month: i64, day: i64) -> i64 {
    let shifted = if month <= 2 { year - 1 } else { year };
    let era = shifted.div_euclid(400);
    let year_of_era = shifted - era * 400;
    let day_of_year = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - DAYS_TO_EPOCH
}

/// Reads a form from the start of `text`, one byte at a time. Every form is ASCII, so a byte that is
/// not the one the form has there, the first byte of a code point past ASCII included, ends it.
struct Cursor<'a> {
    text: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn read(&mut self, kind: TemporalKind) -> Option<Fields> {
        let mut fields = Fields::default();
        if kind != TemporalKind::Time {
            self.date(&mut fields)?;
            if kind == TemporalKind::Date {
                return Some(fields);
            }
            self.byte(b'T')?;
        }
        fields.hour = self.two_digits()?;
        self.byte(b':')?;
        fields.minute = self.two_digits()?;
        if kind == TemporalKind::Instant || self.peek() == Some(b':') {
            self.byte(b':')?;
            fields.second = Some(self.two_digits()?);
            if self.peek() == Some(b'.') {
                self.at += 1;
                // A point with no digits after it is not a fraction.
                let digits = self.digits();
                if !(1..=9).contains(&digits.len()) {
                    return None;
                }
                fields.fraction = true;
                // Nine digits are below 2^32; fewer are scaled up to nine.
                fields.nanosecond = (number(digits) * 10_i64.pow(9 - digits.len() as u32)) as u32;
            }
        }
        if matches!(kind, TemporalKind::OffsetDateTime | TemporalKind::Instant) {
            fields.offset = Some(self.offset()?);
        }
        Some(fields)
    }

    fn date(&mut self, fields: &mut Fields) -> Option<()> {
        let sign = match self.peek() {
            Some(b'+') => 1,
            Some(b'-') => -1,
            _ => 0,
        };
        if sign != 0 {
            self.at += 1;
        }
        // The year is followed by '-', so every digit there is is the year's.
        let digits = self.digits();
        let leading_zero = digits.first() == Some(&b'0');
        let written = match sign {
            0 => digits.len() == 4,
            1 => (5..=10).contains(&digits.len()) && !leading_zero,
            _ => {
                (digits.len() == 4 && digits != b"0000")
                    || ((5..=10).contains(&digits.len()) && !leading_zero)
            }
        };
        if !written {
            return None;
        }
        fields.year = if sign < 0 {
            -number(digits)
        } else {
            number(digits)
        };
        self.byte(b'-')?;
        fields.month = self.two_digits()?;
        self.byte(b'-')?;
        fields.day = self.two_digits()?;
        Some(())
    }

    /// `Z`, or a sign, hours and minutes, and seconds or not: the seconds from UTC it writes, and
    /// whether its minutes and seconds are those of a clock.
    fn offset(&mut self) -> Option<(i64, bool)> {
        let sign = match self.peek()? {
            b'Z' => {
                self.at += 1;
                return Some((0, true));
            }
            b'+' => 1,
            b'-' => -1,
            _ => return None,
        };
        self.at += 1;
        let hour = self.two_digits()?;
        self.byte(b':')?;
        let minute = self.two_digits()?;
        let second = if self.peek() == Some(b':') {
            self.at += 1;
            Some(self.two_digits()?)
        } else {
            None
        };
        let clock = minute <= 59 && second.is_none_or(|second| second <= 59);
        Some((
            sign * (hour * 3600 + minute * 60 + second.unwrap_or(0)),
            clock,
        ))
    }

    fn peek(&self) -> Option<u8> {
        self.text.get(self.at).copied()
    }

    fn byte(&mut self, expected: u8) -> Option<()> {
        (self.peek()? == expected).then(|| self.at += 1)
    }

    fn two_digits(&mut self) -> Option<i64> {
        let digits = self.text.get(self.at..self.at + 2)?;
        if !digits.iter().all(u8::is_ascii_digit) {
            return None;
        }
        self.at += 2;
        Some(number(digits))
    }

    /// The ASCII digits from here, as many as there are.
    fn digits(&mut self) -> &[u8] {
        let from = self.at;
        while self.peek().is_some_and(|b| b.is_ascii_digit()) {
            self.at += 1;
        }
        &self.text[from..self.at]
    }
}

/// The number ASCII `digits` write, no more than ten of them.
fn number(digits: &[u8]) -> i64 {
    digits.iter().fold(0, |n, d| n * 10 + i64::from(d - b'0'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn date(text: &str) -> (i32, u8, u8) {
        let d = read_date(text).unwrap();
        (d.year, d.month, d.day)
    }

    fn time(text: &str) -> (u8, u8, u8, u32) {
        let t = read_time(text).unwrap();
        (t.hour, t.minute, t.second, t.nanosecond)
    }

    /// The date, the time and the offset read, as tuples.
    type OffsetRead = ((i32, u8, u8), (u8, u8, u8, u32), i32);

    fn offset(text: &str) -> OffsetRead {
        let o = read_offset_date_time(text).unwrap();
        let (d, t) = (o.date_time.date, o.date_time.time);
        (
            (d.year, d.month, d.day),
            (t.hour, t.minute, t.second, t.nanosecond),
            o.offset_seconds,
        )
    }

    fn instant(text: &str) -> (i64, u32) {
        let i = read_instant(text).unwrap();
        (i.epoch_second, i.nanosecond)
    }

    // The values below were worked out apart from this crate, with java.time.

    #[test]
    fn a_date_is_read_at_the_ends_of_its_years() {
        assert_eq!(date("+999999999-12-31"), (999_999_999, 12, 31));
        assert_eq!(date("-999999999-01-01"), (-999_999_999, 1, 1));
        assert_eq!(date("-0001-01-01"), (-1, 1, 1));
        assert_eq!(date("0000-01-01"), (0, 1, 1));
        assert_eq!(date("+10000-01-01"), (10_000, 1, 1));
        assert_eq!(date("2024-02-29"), (2024, 2, 29));
    }

    #[test]
    fn a_time_reads_a_second_left_out_as_0_and_a_fraction_as_nanoseconds() {
        assert_eq!(time("00:00"), (0, 0, 0, 0));
        assert_eq!(time("23:59:59"), (23, 59, 59, 0));
        assert_eq!(time("12:34:56.5"), (12, 34, 56, 500_000_000));
        assert_eq!(time("12:34:56.000000001"), (12, 34, 56, 1));
        assert_eq!(time("12:34:56.123456789"), (12, 34, 56, 123_456_789));
        let dt = read_date_time("2026-09-30T23:59:59.999999999").unwrap();
        assert_eq!(
            (
                dt.date.year,
                dt.date.day,
                dt.time.second,
                dt.time.nanosecond
            ),
            (2026, 30, 59, 999_999_999)
        );
    }

    #[test]
    fn a_date_time_with_an_offset_keeps_the_fields_written_and_the_offset_in_seconds() {
        assert_eq!(
            offset("+999999999-12-31T23:59:59-18:00"),
            ((999_999_999, 12, 31), (23, 59, 59, 0), -64_800)
        );
        assert_eq!(offset("2026-09-30T12:34:56+18:00").2, 64_800);
        assert_eq!(offset("2026-09-30T12:34:56+17:59:59").2, 64_799);
        assert_eq!(offset("2026-09-30T12:34:56-05:30:15").2, -19_815);
        assert_eq!(offset("2026-09-30T12:34:56Z").2, 0);
        assert_eq!(
            offset("2026-09-30T12:34:56.5-00:00"),
            ((2026, 9, 30), (12, 34, 56, 500_000_000), 0)
        );
        assert_eq!(
            offset("2026-09-30T12:34+09:00"),
            ((2026, 9, 30), (12, 34, 0, 0), 32_400)
        );
    }

    #[test]
    fn an_instant_is_the_epoch_second_at_or_before_it_and_the_nanoseconds_after() {
        assert_eq!(instant("1970-01-01T00:00:00Z"), (0, 0));
        assert_eq!(instant("1969-12-31T23:59:59.5Z"), (-1, 500_000_000));
        assert_eq!(instant("2026-09-30T23:59:59Z"), (1_790_812_799, 0));
        assert_eq!(instant("2026-09-30T24:00:00Z"), (1_790_812_800, 0));
        assert_eq!(instant("2026-09-30T24:00:00+09:00"), (1_790_780_400, 0));
        assert_eq!(instant("-1000000000-01-01T00:00:00Z"), (INSTANT_MIN, 0));
        assert_eq!(
            instant("+1000000000-12-31T23:59:59.999999999Z"),
            (INSTANT_MAX, 999_999_999)
        );
        assert_eq!(
            instant("-999999999-01-01T00:00:00+18:00"),
            (-31_557_014_135_661_600, 0)
        );
        assert_eq!(
            instant("+999999999-12-31T24:00:00Z"),
            (31_556_889_832_780_800, 0)
        );
    }

    #[test]
    fn a_reader_refuses_as_the_check_does() {
        assert_eq!(
            read_instant("2016-12-31T23:59:60Z"),
            Err(TemporalRefusal::LeapSecond)
        );
        assert_eq!(
            read_instant("+1000000000-12-31T24:00:00Z"),
            Err(TemporalRefusal::Malformed)
        );
        assert_eq!(read_date("2023-02-29"), Err(TemporalRefusal::Malformed));
        assert_eq!(read_time("24:00"), Err(TemporalRefusal::Malformed));
    }

    /// The suite holds that a leap second is refused; which refusal it is, is this implementation's.
    #[test]
    fn a_leap_second_is_refused_as_one_where_its_moment_with_second_59_is_one() {
        let leap = |text| check_temporal(TemporalKind::Instant, text);
        assert_eq!(
            leap("2016-12-31T23:59:60Z"),
            Err(TemporalRefusal::LeapSecond)
        );
        assert_eq!(
            leap("2026-09-30T12:00:60.5Z"),
            Err(TemporalRefusal::LeapSecond)
        );
        assert_eq!(
            leap("2026-02-30T23:59:60Z"),
            Err(TemporalRefusal::Malformed)
        );
        assert_eq!(
            leap("+1000000000-12-31T23:59:60Z"),
            Err(TemporalRefusal::LeapSecond)
        );
        assert_eq!(
            leap("+1000000000-12-31T23:59:60-00:00:01"),
            Err(TemporalRefusal::Malformed)
        );
        assert_eq!(
            check_temporal(TemporalKind::OffsetDateTime, "2016-12-31T23:59:60Z"),
            Err(TemporalRefusal::Malformed)
        );
    }
}
