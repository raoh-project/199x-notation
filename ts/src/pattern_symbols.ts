// A set of the characters a string is made of: Unicode scalar values, every code point but the
// surrogates.
//
// The universe is what text can hold. No text holds a surrogate, so no set here names one: a range
// never holds one, and the complement is taken within the scalar values. That is what lets every
// sequence of symbols a machine reads be a string.
//
// Held as ranges, sorted, apart and never touching, so that one set has one spelling: the first and
// last code point of each, one range after another. A literal is one code point, a class is a union
// of ranges, a negated class is the universe less that union, and . is the universe less the five
// line terminators: the same algebra, so nothing downstream needs to know which shape a set came
// from.

import { LETTERS_AND_DIGITS } from "./pattern_alphabet_tables.ts";

/** A set of scalar values, as sorted inclusive ranges that do not touch, first and last in turn. */
export type Symbols = readonly number[];

export const LAST_SYMBOL = 0x10FFFF;
const SURROGATES_FROM = 0xD800;
const SURROGATES_TO = 0xDFFF;

export function isSurrogate(cp: number): boolean {
  return cp >= SURROGATES_FROM && cp <= SURROGATES_TO;
}

/** The set of `cp` alone, a scalar value. */
export function one(cp: number): Symbols {
  return [cp, cp];
}

/**
 * Every scalar value from `first` to `last`, both ends in it, the surrogates left out. Both ends are
 * scalar values, and `first` is not above `last`.
 */
export function between(first: number, last: number): Symbols {
  return normalized(scalarsIn(first, last, []));
}

/** Appends the runs the scalar values in `first..last` make, none, one or two of them. */
function scalarsIn(first: number, last: number, out: number[]): number[] {
  if (first < SURROGATES_FROM) {
    out.push(first, Math.min(last, SURROGATES_FROM - 1));
  }
  if (last > SURROGATES_TO) {
    out.push(Math.max(first, SURROGATES_TO + 1), last);
  }
  return out;
}

/** The symbols in any of `sets`. */
export function unionOf(...sets: Symbols[]): Symbols {
  return normalized(sets.flat());
}

/**
 * The runs `given` holds, first and last in turn, sorted and joined where they touch or overlap, so
 * that one set has one spelling.
 */
export function normalized(given: readonly number[]): Symbols {
  const runs: [number, number][] = [];
  for (let at = 0; at < given.length; at += 2) {
    runs.push([given[at]!, given[at + 1]!]);
  }
  runs.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const out: number[] = [];
  for (const [first, last] of runs) {
    const n = out.length;
    if (n > 0 && first <= out[n - 1]! + 1) {
      out[n - 1] = Math.max(out[n - 1]!, last);
      continue;
    }
    out.push(first, last);
  }
  return out;
}

/**
 * Whether `cp` is in `ranges`: a search, since a machine asks it of every step at every character,
 * and a class written wide would make a walk as long as the class.
 */
export function has(ranges: readonly number[], cp: number): boolean {
  let low = 0;
  let high = ranges.length >> 1;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (ranges[2 * mid + 1]! < cp) {
      low = mid + 1;
    } else {
      high = mid;
    }
  }
  return low < ranges.length >> 1 && ranges[2 * low]! <= cp;
}

/** Every scalar value `s` does not hold. */
export function not(s: Symbols): Symbols {
  const out: number[] = [];
  let next = 0;
  for (let at = 0; at < s.length; at += 2) {
    if (s[at]! > next) {
      scalarsIn(next, s[at]! - 1, out);
    }
    next = s[at + 1]! + 1;
  }
  if (next <= LAST_SYMBOL) {
    scalarsIn(next, LAST_SYMBOL, out);
  }
  return normalized(out);
}

/** `s` without `those`. */
export function less(s: Symbols, those: Symbols): Symbols {
  return not(unionOf(not(s), those));
}

/** How many symbols `s` holds. */
export function size(s: Symbols): number {
  let n = 0;
  for (let at = 0; at < s.length; at += 2) {
    n += s[at + 1]! - s[at]! + 1;
  }
  return n;
}

/**
 * Whether `cp` is a letter or a decimal digit, which a backslash before it makes an escape with a
 * meaning or none, and never the character itself.
 */
export function isLetterOrDigit(cp: number): boolean {
  return has(LETTERS_AND_DIGITS, cp);
}

/** Every symbol there is: the scalar values. */
export const EVERYTHING: Symbols = [0, SURROGATES_FROM - 1, SURROGATES_TO + 1, LAST_SYMBOL];

/**
 * What a pattern's . leaves out: a line feed, a carriage return, the next-line character and the two
 * separators.
 */
export const LINE_TERMINATORS: Symbols = unionOf(one(0x0A), one(0x0D), one(0x85), one(0x2028), one(0x2029));

/** A pattern's \d: the ten ASCII digits and no other. */
export const DIGITS: Symbols = between(0x30, 0x39);

/** A pattern's \w: the ASCII letters, the ASCII digits and the underscore. */
export const WORD: Symbols = unionOf(between(0x61, 0x7A), between(0x41, 0x5A), DIGITS, one(0x5F));

/**
 * A pattern's \s: a space, a tab, a line feed, a vertical tab, a form feed and a carriage return.
 * Not the White_Space set, which is a separate set the specifications state separately.
 */
export const SPACE: Symbols = unionOf(one(0x20), between(0x09, 0x0D));

// The sets a pattern writes as differences, worked out once rather than at each place a pattern
// writes them.
export const DOT: Symbols = less(EVERYTHING, LINE_TERMINATORS);
export const NOT_DIGITS: Symbols = not(DIGITS);
export const NOT_WORD: Symbols = not(WORD);
export const NOT_SPACE: Symbols = not(SPACE);
