// The lexical grammar of dates, times, date-times, date-times with an offset, and instants.

/** The temporals that have a text form. */
export type TemporalKind =
  /** A day: `yyyy-MM-dd`. */
  | "date"
  /** A time of day: `HH:mm` or `HH:mm:ss`, with a fraction of a second or not. */
  | "time"
  /** A day and a time of day, joined by `T`. */
  | "dateTime"
  /** A day and a time of day, and an offset from UTC: `Z` or `±HH:mm[:ss]`. */
  | "offsetDateTime"
  /** A moment: a day, a time of day with its seconds, and an offset from UTC. */
  | "instant";

/**
 * Why a text is not a temporal of the kind it was to be. The refusals are reasons and not a flag,
 * because they are different things to tell a caller: text that is no temporal, and text that
 * names a leap second.
 */
export type TemporalRefusal =
  /** Not in the form, or in it and naming no day or moment. */
  | "malformed"
  /** An instant whose second is 60. */
  | "leapSecond";

/** What {@link checkTemporal} answers: that a text is admitted, or why it is not. */
export type TemporalAnswer = "admitted" | TemporalRefusal;

/** What a reader answers: the value a text names, or why the text names none. */
export type TemporalRead<T> = { readonly value: T } | { readonly refusal: TemporalRefusal };

/** The least year of a date. */
export const YEAR_MIN = -999_999_999;
/** The greatest year of a date. */
export const YEAR_MAX = 999_999_999;
/**
 * The least moment of an instant, in seconds from 1970-01-01T00:00:00Z: the first second of year
 * -1000000000. A `bigint`, since it is past the integers a `number` holds exactly.
 */
export const INSTANT_MIN = -31_557_014_167_219_200n;
/**
 * The greatest moment of an instant, in seconds from 1970-01-01T00:00:00Z: the last second of year
 * 1000000000.
 */
export const INSTANT_MAX = 31_556_889_864_403_199n;

const SECONDS_PER_DAY = 86_400;
/** The furthest an offset reaches from UTC, eighteen hours. */
const MAX_OFFSET_SECONDS = 18 * 3600;
/** Days from 0000-03-01 to 1970-01-01, which the day count below is taken from. */
const DAYS_TO_EPOCH = 719_468;

/**
 * Whether `text` is a `kind`, and why not where it is not.
 *
 * Each form is a grammar over ASCII, and that grammar alone decides whether a text is in the form.
 * It is read left to right a field at a time, and not by a regular expression. Whether the fields
 * then name a day and a moment that exist is arithmetic on the fields, done here, so neither `Date`
 * nor `Temporal` decides anything: what is admitted does not follow an engine's release.
 *
 * A year is four digits and no sign for 0000 to 9999, and otherwise a sign and no leading zero
 * beyond the four-digit minimum; `-0000` is not year 0. A fraction of a second is one to nine
 * digits after the seconds. An offset is `Z`, or a sign, hours and minutes, and seconds or not.
 *
 * Each temporal is admitted in its own domain. A date, a date-time and a date-time with an offset
 * hold the years from {@link YEAR_MIN} to {@link YEAR_MAX} in the fields they are written in,
 * whatever the offset. An instant holds the moments from {@link INSTANT_MIN} to
 * {@link INSTANT_MAX}, counted in epoch seconds after the offset and an hour 24 have been applied.
 *
 * A date-time with an offset and an instant are two forms and not one. The first is a local
 * date-time beside a displacement: its seconds may be left out, its hour is one a clock shows, and
 * it is admitted by the fields it writes. The second is a moment: its seconds are written,
 * `24:00:00` with nothing after it is the start of the next day, and it is admitted by the moment
 * it names. An instant whose second is 60 names a leap second, which is refused as one where the
 * moment it is said at, with second 59, is one an instant holds.
 *
 * `text` may be any string: a code unit past ASCII, half of a surrogate pair included, is in no
 * form. A `kind` that is none of the kinds is a mistake in the calling program and not text that
 * is malformed, and throws a `TypeError`.
 */
export function checkTemporal(kind: TemporalKind, text: string): TemporalAnswer {
  const read = admit(kind, text);
  return read instanceof Fields ? "admitted" : read;
}

/**
 * A date, as {@link readDate} reads it.
 *
 * The readers are this package's own, and not a rule the implementations share: the suite holds
 * which text is admitted and not what it names. They give what {@link checkTemporal} read, for a
 * caller to build its own value from rather than read the text again.
 *
 * Each type holds the fields a value is built from, and a fact of the text where both texts are
 * admitted and a caller's own rule tells them apart: whether a time writes a fraction of a second
 * ({@link TemporalTime.nanosecond}), which Souther refuses where Raoh reads it. A difference that
 * no caller's rule tells apart is not kept: `Z`, `+00:00` and `-00:00` are the offset 0, a second
 * left out is second 0, and a fraction's trailing zeros are not counted.
 */
export interface TemporalDate {
  /** The year, from {@link YEAR_MIN} to {@link YEAR_MAX}. */
  readonly year: number;
  /** The month, from 1 to 12. */
  readonly month: number;
  /** The day of the month, from 1 to the month's last. */
  readonly day: number;
}

/** A time of day, as {@link readTime} reads it. */
export interface TemporalTime {
  /** The hour, from 0 to 23. */
  readonly hour: number;
  /** The minute, from 0 to 59. */
  readonly minute: number;
  /** The second, from 0 to 59, and 0 where it is left out. */
  readonly second: number;
  /**
   * The fraction of the second, in nanoseconds, where one is written: its digits followed by zeros
   * to nine, so `.000` is 0. `undefined` where no fraction is written.
   */
  readonly nanosecond: number | undefined;
}

/** A date and a time of day, as {@link readDateTime} reads them. */
export interface TemporalDateTime {
  /** The date. */
  readonly date: TemporalDate;
  /** The time of day. */
  readonly time: TemporalTime;
}

/** A date and a time of day, and an offset from UTC, as {@link readOffsetDateTime} reads them. */
export interface TemporalOffsetDateTime {
  /** The date and the time of day, as written. */
  readonly dateTime: TemporalDateTime;
  /** The offset in seconds east of UTC, from -64800 to 64800: 0 for `Z`, `+00:00` and `-00:00`. */
  readonly offsetSeconds: number;
}

/** A moment, as {@link readInstant} reads it. */
export interface TemporalInstant {
  /**
   * The seconds from 1970-01-01T00:00:00Z to the second at or before the moment, from
   * {@link INSTANT_MIN} to {@link INSTANT_MAX}.
   */
  readonly epochSecond: bigint;
  /** The nanoseconds from that second to the moment. */
  readonly nanosecond: number;
}

/** The date `text` names, where {@link checkTemporal} admits it as a `date`. */
export function readDate(text: string): TemporalRead<TemporalDate> {
  return reading(admit("date", text), (fields) => fields.date());
}

/** The time of day `text` names, where {@link checkTemporal} admits it as a `time`. */
export function readTime(text: string): TemporalRead<TemporalTime> {
  return reading(admit("time", text), (fields) => fields.time());
}

/** The date and time of day `text` names, where {@link checkTemporal} admits it as a `dateTime`. */
export function readDateTime(text: string): TemporalRead<TemporalDateTime> {
  return reading(admit("dateTime", text), (fields) => fields.dateTime());
}

/**
 * The date, time of day and offset `text` names, where {@link checkTemporal} admits it as an
 * `offsetDateTime`. The date and time are the ones written, not moved by the offset.
 */
export function readOffsetDateTime(text: string): TemporalRead<TemporalOffsetDateTime> {
  return reading(admit("offsetDateTime", text), (fields) => ({
    dateTime: fields.dateTime(),
    offsetSeconds: fields.offsetSeconds(),
  }));
}

/**
 * The moment `text` names, where {@link checkTemporal} admits it as an `instant`: the epoch second
 * admitting it worked out, with the offset applied and an hour 24 read as the start of the next
 * day.
 */
export function readInstant(text: string): TemporalRead<TemporalInstant> {
  return reading(admit("instant", text), (fields) => ({
    epochSecond: fields.epochSecond,
    nanosecond: fields.nanosecond ?? 0,
  }));
}

function reading<T>(read: Fields | TemporalRefusal, value: (fields: Fields) => T): TemporalRead<T> {
  return read instanceof Fields ? { value: value(read) } : { refusal: read };
}

/**
 * The fields of `text`, where it is a `kind`, and why not where it is not. This is the one reading
 * of the text: {@link checkTemporal} and every reader answer from it.
 */
function admit(kind: TemporalKind, text: string): Fields | TemporalRefusal {
  if (!KINDS.has(kind)) {
    throw new TypeError(`${String(kind)} is none of the temporal kinds`);
  }
  const cursor = new Cursor(text);
  const fields = cursor.read(kind);
  if (fields === undefined || cursor.at !== text.length) {
    return "malformed";
  }
  let admitted: boolean;
  switch (kind) {
    case "date":
      admitted = fields.dateExists();
      break;
    case "time":
      admitted = fields.timeExists();
      break;
    case "dateTime":
      admitted = fields.dateExists() && fields.timeExists();
      break;
    case "offsetDateTime":
      admitted = fields.dateExists() && fields.timeExists() && fields.offsetExists();
      break;
    case "instant": {
      if (!fields.offsetExists()) {
        return "malformed";
      }
      if (fields.second === 60) {
        // A second that does not exist. Whether the moment it is said at does is asked with the
        // second every minute has.
        return fields.momentOf(59) === undefined ? "malformed" : "leapSecond";
      }
      const epochSecond = fields.momentOf(fields.second ?? 0);
      admitted = epochSecond !== undefined;
      if (epochSecond !== undefined) {
        fields.epochSecond = epochSecond;
      }
      break;
    }
  }
  return admitted ? fields : "malformed";
}

const KINDS: ReadonlySet<string> = new Set<TemporalKind>(["date", "time", "dateTime", "offsetDateTime", "instant"]);

/** The fields a text in one of the forms writes, as numbers, before any is asked whether it exists. */
class Fields {
  year = 0;
  month = 0;
  day = 0;
  hour = 0;
  minute = 0;
  second: number | undefined;
  /** The fraction of the second, in nanoseconds, where one is written. */
  nanosecond: number | undefined;
  /** The moment an instant names, in seconds from the epoch, once it has been admitted. */
  epochSecond = 0n;
  /** Seconds from UTC, as written, where there is an offset. */
  offset: number | undefined;
  /** Whether the offset's minutes and seconds are those of a clock. */
  offsetOnClock = false;

  /** Whether the date is one there is: a year within the range, and a day the month has. */
  dateExists(): boolean {
    return this.year >= YEAR_MIN && this.year <= YEAR_MAX && this.dayExists();
  }

  dayExists(): boolean {
    return this.month >= 1 && this.month <= 12 && this.day >= 1
      && this.day <= lengthOfMonth(this.year, this.month);
  }

  /** Whether the time of day is one a clock shows. Hour 24 is not, whatever follows. */
  timeExists(): boolean {
    return this.hour <= 23 && this.minute <= 59 && (this.second === undefined || this.second <= 59);
  }

  /**
   * Whether the offset reaches no further than eighteen hours, with minutes and seconds that are
   * those of a clock.
   */
  offsetExists(): boolean {
    return this.offset !== undefined && this.offsetOnClock && Math.abs(this.offset) <= MAX_OFFSET_SECONDS;
  }

  /**
   * The moment the text names, with `second` as its second, in seconds from the epoch, where it is
   * one and within the range of an instant. `24:00:00` is the start of the next day, and only when
   * nothing follows it.
   */
  momentOf(second: number): bigint | undefined {
    const timeExists = this.hour === 24
      ? this.minute === 0 && second === 0 && this.nanosecond === undefined
      : this.hour <= 23 && this.minute <= 59 && second <= 59;
    if (!timeExists || !this.dayExists()) {
      return undefined;
    }
    // The day count is within the integers a number holds exactly, and its seconds are not.
    const epochSecond = BigInt(epochDay(this.year, this.month, this.day)) * BigInt(SECONDS_PER_DAY)
      + BigInt(this.hour * 3600 + this.minute * 60 + second - this.offsetSeconds());
    return epochSecond >= INSTANT_MIN && epochSecond <= INSTANT_MAX ? epochSecond : undefined;
  }

  /** The offset in seconds east of UTC, 0 where there is none. */
  offsetSeconds(): number {
    // `-00:00` is the offset 0, and not -0.
    return this.offset === undefined || this.offset === 0 ? 0 : this.offset;
  }

  // The fields below are read only of text that is admitted, whose fields are within the ranges
  // the types hold.

  date(): TemporalDate {
    return { year: this.year, month: this.month, day: this.day };
  }

  time(): TemporalTime {
    return { hour: this.hour, minute: this.minute, second: this.second ?? 0, nanosecond: this.nanosecond };
  }

  dateTime(): TemporalDateTime {
    return { date: this.date(), time: this.time() };
  }
}

function lengthOfMonth(year: number, month: number): number {
  switch (month) {
    case 2:
      return isLeap(year) ? 29 : 28;
    case 4:
    case 6:
    case 9:
    case 11:
      return 30;
    default:
      return 31;
  }
}

/** Whether `year` of the proleptic Gregorian calendar has a 29 February. */
function isLeap(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

/**
 * Days from 1970-01-01 to a day of the proleptic Gregorian calendar that exists. The year runs from
 * March, so the leap day is the last of it.
 */
function epochDay(year: number, month: number, day: number): number {
  const shifted = month <= 2 ? year - 1 : year;
  const era = Math.floor(shifted / 400);
  const yearOfEra = shifted - era * 400;
  const dayOfYear = Math.floor((153 * (month > 2 ? month - 3 : month + 9) + 2) / 5) + day - 1;
  const dayOfEra = yearOfEra * 365 + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100) + dayOfYear;
  return era * 146_097 + dayOfEra - DAYS_TO_EPOCH;
}

const ZERO = 0x30;
const NINE = 0x39;
const COLON = 0x3A;
const FULL_STOP = 0x2E;
const HYPHEN = 0x2D;
const PLUS = 0x2B;
const T = 0x54;
const Z = 0x5A;

/**
 * Reads a form from the start of `text`, one code unit at a time. Every form is ASCII, so a code
 * unit that is not the one the form has there, any past ASCII included, ends it.
 */
class Cursor {
  private readonly text: string;
  at = 0;

  constructor(text: string) {
    this.text = text;
  }

  read(kind: TemporalKind): Fields | undefined {
    const fields = new Fields();
    if (kind !== "time") {
      if (!this.date(fields)) {
        return undefined;
      }
      if (kind === "date") {
        return fields;
      }
      if (!this.take(T)) {
        return undefined;
      }
    }
    const hour = this.twoDigits();
    if (hour === undefined || !this.take(COLON)) {
      return undefined;
    }
    const minute = this.twoDigits();
    if (minute === undefined) {
      return undefined;
    }
    fields.hour = hour;
    fields.minute = minute;
    if (kind === "instant" || this.peek() === COLON) {
      if (!this.take(COLON)) {
        return undefined;
      }
      fields.second = this.twoDigits();
      if (fields.second === undefined) {
        return undefined;
      }
      if (this.take(FULL_STOP)) {
        // A point with no digits after it is not a fraction.
        const from = this.at;
        const digits = this.digits();
        if (digits < 1 || digits > 9) {
          return undefined;
        }
        fields.nanosecond = this.number(from, digits) * 10 ** (9 - digits);
      }
    }
    if (kind === "offsetDateTime" || kind === "instant") {
      if (!this.offset(fields)) {
        return undefined;
      }
    }
    return fields;
  }

  private date(fields: Fields): boolean {
    const negative = this.take(HYPHEN);
    const signed = negative || this.take(PLUS);
    // The year is followed by '-', so every digit there is is the year's.
    const from = this.at;
    const digits = this.digits();
    const leadingZero = digits > 0 && this.text.charCodeAt(from) === ZERO;
    const written = !signed
      ? digits === 4
      : (negative && digits === 4 && this.text.slice(from, from + 4) !== "0000")
        || (digits >= 5 && digits <= 10 && !leadingZero);
    if (!written) {
      return false;
    }
    const year = this.number(from, digits);
    fields.year = negative ? -year : year;
    if (!this.take(HYPHEN)) {
      return false;
    }
    const month = this.twoDigits();
    if (month === undefined || !this.take(HYPHEN)) {
      return false;
    }
    const day = this.twoDigits();
    if (day === undefined) {
      return false;
    }
    fields.month = month;
    fields.day = day;
    return true;
  }

  /**
   * `Z`, or a sign, hours and minutes, and seconds or not: the seconds from UTC it writes, and
   * whether its minutes and seconds are those of a clock.
   */
  private offset(fields: Fields): boolean {
    if (this.take(Z)) {
      fields.offset = 0;
      fields.offsetOnClock = true;
      return true;
    }
    const sign = this.take(PLUS) ? 1 : this.take(HYPHEN) ? -1 : 0;
    if (sign === 0) {
      return false;
    }
    const hour = this.twoDigits();
    if (hour === undefined || !this.take(COLON)) {
      return false;
    }
    const minute = this.twoDigits();
    if (minute === undefined) {
      return false;
    }
    let second: number | undefined;
    if (this.take(COLON)) {
      second = this.twoDigits();
      if (second === undefined) {
        return false;
      }
    }
    fields.offset = sign * (hour * 3600 + minute * 60 + (second ?? 0));
    fields.offsetOnClock = minute <= 59 && (second === undefined || second <= 59);
    return true;
  }

  private peek(): number {
    return this.text.charCodeAt(this.at);
  }

  private take(expected: number): boolean {
    if (this.peek() !== expected) {
      return false;
    }
    this.at++;
    return true;
  }

  private twoDigits(): number | undefined {
    if (!isDigit(this.text.charCodeAt(this.at)) || !isDigit(this.text.charCodeAt(this.at + 1))) {
      return undefined;
    }
    this.at += 2;
    return this.number(this.at - 2, 2);
  }

  /** Reads the ASCII digits from here, as many as there are, and answers how many. */
  private digits(): number {
    const from = this.at;
    while (isDigit(this.peek())) {
      this.at++;
    }
    return this.at - from;
  }

  /** The number the `count` ASCII digits at `from` write, no more than ten of them. */
  private number(from: number, count: number): number {
    let n = 0;
    for (let at = from; at < from + count; at++) {
      n = n * 10 + (this.text.charCodeAt(at) - ZERO);
    }
    return n;
  }
}

/** Whether `unit` is an ASCII digit. `charCodeAt` past the end is NaN, which is none. */
function isDigit(unit: number): boolean {
  return unit >= ZERO && unit <= NINE;
}
