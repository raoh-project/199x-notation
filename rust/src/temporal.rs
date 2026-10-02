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
    let mut cursor = Cursor {
        text: text.as_bytes(),
        at: 0,
    };
    let fields = cursor
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
                return if fields.moment_exists(59) {
                    Err(TemporalRefusal::LeapSecond)
                } else {
                    Err(TemporalRefusal::Malformed)
                };
            }
            fields.moment_exists(fields.second.unwrap_or(0))
        }
    };
    if admitted {
        Ok(())
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

    /// Whether the moment the text names is within the range of an instant, with `second` as its
    /// second. `24:00:00` is the start of the next day, and only when nothing follows it.
    fn moment_exists(&self, second: i64) -> bool {
        let time_exists = if self.hour == 24 {
            self.minute == 0 && second == 0 && !self.fraction
        } else {
            self.hour <= 23 && self.minute <= 59 && second <= 59
        };
        if !time_exists || !self.day_exists() {
            return false;
        }
        let offset = self.offset.map_or(0, |(seconds, _)| seconds);
        let epoch_second = epoch_day(self.year, self.month, self.day) * SECONDS_PER_DAY
            + self.hour * 3600
            + self.minute * 60
            + second
            - offset;
        (INSTANT_MIN..=INSTANT_MAX).contains(&epoch_second)
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
