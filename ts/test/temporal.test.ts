// What the readers give, which the suite does not hold: the values below were worked out apart from
// this package, with java.time, as the Rust implementation's tests were.

import assert from "node:assert/strict";
import { it } from "node:test";
import {
  INSTANT_MAX,
  INSTANT_MIN,
  checkTemporal,
  readDate,
  readDateTime,
  readInstant,
  readOffsetDateTime,
  readTime,
  type TemporalRead,
} from "../src/index.ts";

function value<T>(read: TemporalRead<T>): T {
  assert.ok("value" in read, `refused: ${"refusal" in read ? read.refusal : ""}`);
  return read.value;
}

const date = (text: string) => {
  const d = value(readDate(text));
  return [d.year, d.month, d.day];
};

const time = (text: string) => {
  const t = value(readTime(text));
  return [t.hour, t.minute, t.second, t.nanosecond];
};

const offset = (text: string) => {
  const o = value(readOffsetDateTime(text));
  const { date: d, time: t } = o.dateTime;
  return [[d.year, d.month, d.day], [t.hour, t.minute, t.second, t.nanosecond], o.offsetSeconds];
};

const instant = (text: string) => {
  const i = value(readInstant(text));
  return [i.epochSecond, i.nanosecond];
};

it("a date is read at the ends of its years", () => {
  assert.deepEqual(date("+999999999-12-31"), [999_999_999, 12, 31]);
  assert.deepEqual(date("-999999999-01-01"), [-999_999_999, 1, 1]);
  assert.deepEqual(date("-0001-01-01"), [-1, 1, 1]);
  assert.deepEqual(date("0000-01-01"), [0, 1, 1]);
  assert.deepEqual(date("+10000-01-01"), [10_000, 1, 1]);
  assert.deepEqual(date("2024-02-29"), [2024, 2, 29]);
});

it("a time reads a second left out as 0 and a fraction as nanoseconds", () => {
  assert.deepEqual(time("00:00"), [0, 0, 0, undefined]);
  assert.deepEqual(time("23:59:59"), [23, 59, 59, undefined]);
  assert.deepEqual(time("12:34:56.5"), [12, 34, 56, 500_000_000]);
  assert.deepEqual(time("12:34:56.000000001"), [12, 34, 56, 1]);
  assert.deepEqual(time("12:34:56.123456789"), [12, 34, 56, 123_456_789]);
  const dt = value(readDateTime("2026-09-30T23:59:59.999999999"));
  assert.deepEqual([dt.date.year, dt.date.day, dt.time.second, dt.time.nanosecond], [2026, 30, 59, 999_999_999]);
});

// Souther refuses a time that writes a fraction, of nought too, where Raoh reads one; the value
// alone cannot tell `.000` from no fraction, so the reader keeps which was written.
it("a fraction of nought is told from no fraction", () => {
  assert.equal(value(readTime("09:30:00")).nanosecond, undefined);
  assert.equal(value(readTime("09:30:00.000")).nanosecond, 0);
  assert.equal(value(readDateTime("2026-09-30T09:30:00")).time.nanosecond, undefined);
  assert.equal(value(readDateTime("2026-09-30T09:30:00.000")).time.nanosecond, 0);
  assert.equal(value(readOffsetDateTime("2026-09-30T09:30:00Z")).dateTime.time.nanosecond, undefined);
  assert.equal(value(readOffsetDateTime("2026-09-30T09:30:00.000Z")).dateTime.time.nanosecond, 0);
  // An instant is a moment: the fraction is its nanoseconds, written or not.
  assert.equal(value(readInstant("2026-09-30T09:30:00.000Z")).nanosecond, 0);
});

it("a date-time with an offset keeps the fields written and the offset in seconds", () => {
  assert.deepEqual(offset("+999999999-12-31T23:59:59-18:00"),
    [[999_999_999, 12, 31], [23, 59, 59, undefined], -64_800]);
  assert.equal(offset("2026-09-30T12:34:56+18:00")[2], 64_800);
  assert.equal(offset("2026-09-30T12:34:56+17:59:59")[2], 64_799);
  assert.equal(offset("2026-09-30T12:34:56-05:30:15")[2], -19_815);
  assert.equal(offset("2026-09-30T12:34:56Z")[2], 0);
  // -00:00 is the offset 0, and not a negative zero a caller would have to tell from it.
  assert.ok(Object.is(offset("2026-09-30T12:34:56.5-00:00")[2], 0));
  assert.deepEqual(offset("2026-09-30T12:34:56.5-00:00"), [[2026, 9, 30], [12, 34, 56, 500_000_000], 0]);
  assert.deepEqual(offset("2026-09-30T12:34+09:00"), [[2026, 9, 30], [12, 34, 0, undefined], 32_400]);
});

it("an instant is the epoch second at or before it and the nanoseconds after", () => {
  assert.deepEqual(instant("1970-01-01T00:00:00Z"), [0n, 0]);
  assert.deepEqual(instant("1969-12-31T23:59:59.5Z"), [-1n, 500_000_000]);
  assert.deepEqual(instant("2026-09-30T23:59:59Z"), [1_790_812_799n, 0]);
  assert.deepEqual(instant("2026-09-30T24:00:00Z"), [1_790_812_800n, 0]);
  assert.deepEqual(instant("2026-09-30T24:00:00+09:00"), [1_790_780_400n, 0]);
  assert.deepEqual(instant("-1000000000-01-01T00:00:00Z"), [INSTANT_MIN, 0]);
  assert.deepEqual(instant("+1000000000-12-31T23:59:59.999999999Z"), [INSTANT_MAX, 999_999_999]);
  assert.deepEqual(instant("-999999999-01-01T00:00:00+18:00"), [-31_557_014_135_661_600n, 0]);
  assert.deepEqual(instant("+999999999-12-31T24:00:00Z"), [31_556_889_832_780_800n, 0]);
});

it("a reader refuses as the check does", () => {
  assert.deepEqual(readInstant("2016-12-31T23:59:60Z"), { refusal: "leapSecond" });
  assert.deepEqual(readInstant("+1000000000-12-31T24:00:00Z"), { refusal: "malformed" });
  assert.deepEqual(readDate("2023-02-29"), { refusal: "malformed" });
  assert.deepEqual(readTime("24:00"), { refusal: "malformed" });
});

// The suite holds that a leap second is refused; which refusal it is, is this implementation's.
it("a leap second is refused as one where its moment with second 59 is one", () => {
  const leap = (text: string) => checkTemporal("instant", text);
  assert.equal(leap("2016-12-31T23:59:60Z"), "leapSecond");
  assert.equal(leap("2026-09-30T12:00:60.5Z"), "leapSecond");
  assert.equal(leap("2026-02-30T23:59:60Z"), "malformed");
  assert.equal(leap("+1000000000-12-31T23:59:60Z"), "leapSecond");
  assert.equal(leap("+1000000000-12-31T23:59:60-00:00:01"), "malformed");
  assert.equal(checkTemporal("offsetDateTime", "2016-12-31T23:59:60Z"), "malformed");
});

// What only a JavaScript string can hold, and only a JavaScript caller can pass.
it("text past ASCII is in no form, half of a surrogate pair included", () => {
  assert.equal(checkTemporal("date", "2026-09-3٠"), "malformed");
  assert.equal(checkTemporal("date", "2026-09-30\uD800"), "malformed");
  assert.equal(checkTemporal("time", "\uDC0012:00"), "malformed");
  assert.equal(checkTemporal("time", "１２:00"), "malformed");
});

it("a kind that is none of the kinds is a mistake of the caller's", () => {
  assert.throws(() => checkTemporal("week" as never, "2026-W40"), TypeError);
});
