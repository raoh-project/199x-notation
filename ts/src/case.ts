// Unicode 18.0.0's default case conversion: the full mapping of UnicodeData.txt and
// SpecialCasing.txt, with no locale or language tailoring.

import {
  CASE_CONTEXT_BLOCKS,
  CASE_CONTEXT_PAGES,
  FINAL_SIGMA_FROM,
  FINAL_SIGMA_TO,
  LOWER_FROM,
  LOWER_POSITION_BLOCKS,
  LOWER_POSITION_PAGES,
  LOWER_TO,
  UPPER_FROM,
  UPPER_POSITION_BLOCKS,
  UPPER_POSITION_PAGES,
  UPPER_TO,
} from "./case_tables.ts";
import { checkedBound, indexOf, scalarsIn, stop, valueAt, Written } from "./tables.ts";

/**
 * `text` in lowercase: Unicode 18.0.0's untailored full mapping, with no locale or language
 * tailoring. One code point can map to several. A Greek capital sigma becomes the final form only at
 * the end of a cased run, which is the one condition the default mapping carries that is context
 * rather than locale: `lowercase("ΟΣ")` is `"ος"` and `lowercase("ΟΣΑ")` is `"οσα"`.
 *
 * Not `String.prototype.toLowerCase`, which maps by the Unicode version of the engine, nor
 * `toLocaleLowerCase`, which tailors by locale.
 *
 * A case mapping is not closed under any normalization form, so a caller that holds its text in one
 * normalizes the answer. `text` is well formed (see {@link illFormedAt}); this does not ask.
 */
export function lowercase(text: string): string {
  return mapped(text, true, Number.POSITIVE_INFINITY)!;
}

/**
 * `text` in uppercase, by the same untailored full mapping as {@link lowercase}: one code point can
 * widen to several, so `uppercase("straße")` is `"STRASSE"`, and no locale narrows it, so a Turkish
 * `i` still becomes `I`.
 */
export function uppercase(text: string): string {
  return mapped(text, false, Number.POSITIVE_INFINITY)!;
}

/**
 * {@link lowercase} where that is no longer than `longest` scalar values, and `undefined` where it
 * is longer, which is found out before more than `longest` is written. How much of `text` it reads
 * turns on `longest` and not on the length of `text`: it reads no further once what it has read shows
 * the answer to be longer. `longest` is an integer a number holds exactly, or this throws a
 * `RangeError`.
 */
export function lowercaseWithin(text: string, longest: number): string | undefined {
  return mapped(text, true, checkedBound(longest));
}

/** {@link uppercase} where that is no longer than `longest` scalar values, as {@link lowercaseWithin}. */
export function uppercaseWithin(text: string, longest: number): string | undefined {
  return mapped(text, false, checkedBound(longest));
}

// What CASE_CONTEXT holds of a code point, a bit each.
const CASED = 1;
const CASE_IGNORABLE = 2;
const FINAL_SIGMA_NAMED = 4;

/** Whether CASE_CONTEXT holds `bit` of `cp`. */
function hasContext(cp: number, bit: number): boolean {
  return (valueAt(CASE_CONTEXT_BLOCKS, CASE_CONTEXT_PAGES, cp) & bit) !== 0;
}

/** One of the two mappings, as the conversion reads it. */
interface Mapping {
  readonly blocks: string;
  readonly pages: string;
  readonly to: readonly string[];
  /**
   * What each ASCII character maps to, as a code unit, where the mapping makes it one ASCII
   * character and no `Final_Sigma` entry names it, and -1 where the tables are asked.
   */
  readonly ascii: Int16Array;
}

let lower: Mapping | undefined;
let upper: Mapping | undefined;

/**
 * The mapping of the direction asked, made the first time it is asked: the ASCII row is read off
 * the tables, so that most text is mapped a unit at a time without a rule of its own about ASCII,
 * and nothing is worked out when the module loads.
 */
function mappingOf(toLower: boolean): Mapping {
  if (toLower) {
    lower ??= mappingFrom(LOWER_POSITION_BLOCKS, LOWER_POSITION_PAGES, LOWER_FROM, LOWER_TO, true);
    return lower;
  }
  upper ??= mappingFrom(UPPER_POSITION_BLOCKS, UPPER_POSITION_PAGES, UPPER_FROM, UPPER_TO, false);
  return upper;
}

function mappingFrom(blocks: string, pages: string, from: readonly number[], to: readonly string[],
  toLower: boolean): Mapping {
  const ascii = new Int16Array(0x80);
  for (let c = 0; c < 0x80; c++) {
    const at = indexOf(from, c);
    const mappedTo = at < 0 ? undefined : to[at]!;
    ascii[c] = toLower && indexOf(FINAL_SIGMA_FROM, c) >= 0 ? -1
      : mappedTo === undefined ? c
      : mappedTo.length === 1 && mappedTo.charCodeAt(0) < 0x80 ? mappedTo.charCodeAt(0)
      : -1;
  }
  return { blocks, pages, to, ascii };
}

/**
 * The mapped text, or `undefined` where it is longer than `longest`.
 *
 * The text is read where it is, and not taken out first: `Final_Sigma` looks either side of a sigma
 * for as many `Case_Ignorable` code points as there are, and looks at them in the text. What is
 * bounded is what is written: each code point's mapping is measured before any of it is, so the
 * answer never holds more than `longest`, nor part of a mapping that would take it past. Every code
 * point maps to at least one, which the generator checks, so the text is read no further than one
 * code point past `longest` either: what maps to itself is gone past up to there, and `Final_Sigma`
 * looks past no more `Case_Ignorable` code points after a sigma than the answer has room for.
 *
 * Text that maps to itself is answered with itself. Otherwise what maps to itself is copied a run
 * at a time, from `kept`, and the answer is made only once a code point that changes is met. Whether
 * one does is read off the position table, which answers where its mapping is as well.
 */
function mapped(s: string, toLower: boolean, longest: number): string | undefined {
  // Even the empty text is longer than a negative bound.
  if (longest < 0) {
    return undefined;
  }
  const { blocks, pages, to, ascii } = mappingOf(toLower);
  // The answer up to kept, where a code point that changes has been met.
  let out: Written | undefined;
  let kept = 0;
  let written = 0;
  for (let at = 0; at < s.length;) {
    const same = sameUpTo(s, at, longest - written, ascii, blocks, pages);
    if (same.end > at) {
      written += same.count;
      if (written > longest) {
        return undefined;
      }
      at = same.end;
      if (at === s.length) {
        break;
      }
    }
    const cp = s.codePointAt(at)!;
    const after = at + (cp > 0xFFFF ? 2 : 1);
    if (cp < 0x80 && ascii[cp]! >= 0) {
      out ??= new Written();
      out.slice(s, kept, at);
      // The ASCII from here that changes is written as it is mapped, a character at a time.
      while (true) {
        if (written === longest) {
          return undefined;
        }
        written++;
        out.unit(ascii[s.charCodeAt(at)]!);
        at++;
        if (at === s.length) {
          break;
        }
        const next = s.charCodeAt(at);
        if (next >= 0x80 || ascii[next]! < 0 || ascii[next] === next) {
          break;
        }
      }
      kept = at;
      continue;
    }
    let mappedTo = to[valueAt(blocks, pages, cp) - 1]!;
    if (toLower && hasContext(cp, FINAL_SIGMA_NAMED)) {
      // The sigma is at least one scalar value of the answer, as every code point is, and each
      // Case_Ignorable one after it is another.
      if (written === longest) {
        return undefined;
      }
      const isFinal = isFinalSigma(s, at, after, longest - written - 1);
      if (isFinal === undefined) {
        return undefined;
      }
      if (isFinal) {
        mappedTo = FINAL_SIGMA_TO[indexOf(FINAL_SIGMA_FROM, cp)]!;
      }
    }
    const length = scalarsIn(mappedTo);
    if (length > longest - written) {
      return undefined;
    }
    written += length;
    out ??= new Written();
    out.slice(s, kept, at);
    out.text(mappedTo);
    kept = after;
    at = after;
  }
  if (out === undefined) {
    return s;
  }
  out.slice(s, kept, s.length);
  return out.toString();
}

/**
 * Where the code points `s` has from `at` that the mapping leaves as they are end: the first one
 * from there that it changes, or the end of the text, or where it has gone past more than `room` of
 * them; and how many code points it went past.
 */
function sameUpTo(s: string, at: number, room: number, ascii: Int16Array, blocks: string, pages: string):
  { end: number; count: number } {
  const from = at;
  let pairs = 0;
  while (at < s.length && at - from - pairs <= room) {
    const end = stop(s, at, room - (at - from - pairs));
    while (at < end) {
      const c = s.charCodeAt(at);
      if (c < 0x80) {
        if (ascii[c] !== c) {
          return { end: at, count: at - from - pairs };
        }
        at++;
        continue;
      }
      const cp = s.codePointAt(at)!;
      if (valueAt(blocks, pages, cp) !== 0) {
        return { end: at, count: at - from - pairs };
      }
      if (cp > 0xFFFF) {
        pairs++;
        at += 2;
      } else {
        at++;
      }
    }
  }
  return { end: at, count: at - from - pairs };
}

/**
 * Unicode's `Final_Sigma` condition of the code point between `at` and `after`: preceded, skipping
 * `Case_Ignorable` code points, by a `Cased` one, and not followed, skipping the same way, by
 * another `Cased` one. Looked for as far as the text goes rather than over a window, since what is
 * skipped is decided by the property and not by a count. `undefined` where more than `most`
 * `Case_Ignorable` code points follow it, which leaves it undecided.
 */
function isFinalSigma(s: string, at: number, after: number, most: number): boolean | undefined {
  let precededByCased = false;
  for (let j = at; j > 0;) {
    const cp = codePointBefore(s, j);
    j -= cp > 0xFFFF ? 2 : 1;
    if (hasContext(cp, CASE_IGNORABLE)) {
      continue;
    }
    precededByCased = hasContext(cp, CASED);
    break;
  }
  if (!precededByCased) {
    return false;
  }
  let skipped = 0;
  for (let j = after; j < s.length;) {
    const cp = s.codePointAt(j)!;
    j += cp > 0xFFFF ? 2 : 1;
    if (hasContext(cp, CASE_IGNORABLE)) {
      if (skipped === most) {
        return undefined;
      }
      skipped++;
      continue;
    }
    return !hasContext(cp, CASED);
  }
  return true;
}

/** The code point that ends at `at`, a pair where the two units before it are one. */
function codePointBefore(s: string, at: number): number {
  const low = s.charCodeAt(at - 1);
  if (low >= 0xDC00 && low <= 0xDFFF && at >= 2) {
    const high = s.charCodeAt(at - 2);
    if (high >= 0xD800 && high <= 0xDBFF) {
      return (high - 0xD800) * 0x400 + (low - 0xDC00) + 0x10000;
    }
  }
  return low;
}
