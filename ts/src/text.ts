// Text as a sequence of Unicode scalar values: whether a string is one, its length and its order.
//
// A JavaScript string is UTF-16 code units, and can hold half of a surrogate pair, which is no
// scalar value. Every rule here is stated of text that is a sequence of scalar values, which a
// string is where it is well formed. `illFormedAt` is the question a caller asks before it takes
// text in; the other functions take well-formed text and do not ask it.

/**
 * Where `s` holds half of a surrogate pair with no other half, as the index of that code unit, or
 * -1 where `s` is well formed and so is a sequence of scalar values.
 */
export function illFormedAt(s: string): number {
  // Most text is well formed, and the engine says so far faster than a loop here. Only text that
  // is not is gone over again for the place.
  if (s.isWellFormed()) {
    return -1;
  }
  for (let at = 0; at < s.length; at++) {
    const unit = s.charCodeAt(at);
    if (unit >= 0xD800 && unit <= 0xDBFF && isLow(s.charCodeAt(at + 1))) {
      at++;
    } else if (unit >= 0xD800 && unit <= 0xDFFF) {
      return at;
    }
  }
  throw new Error("isWellFormed refused text this scan reads as well formed");
}

/**
 * How many scalar values `s` is made of, which is the length every rule here measures text in,
 * and not `s.length`, which counts UTF-16 code units.
 *
 * `s` is well formed (see {@link illFormedAt}); this does not ask.
 */
export function scalarCount(s: string): number {
  // Every scalar value past U+FFFF is two code units, the second of them a low surrogate.
  let count = s.length;
  for (let at = 0; at < s.length; at++) {
    if (isLow(s.charCodeAt(at))) {
      count--;
    }
  }
  return count;
}

/**
 * Where `a` stands against `b`: the first scalar value where they differ decides, and where one is
 * a prefix of the other the shorter is below. It answers -1, 0 or 1 as `a` stands below, with or
 * above `b`.
 *
 * Not `<`, which compares UTF-16 code units, in which a scalar value past U+FFFF stands below
 * U+E000. `a` and `b` are well formed (see {@link illFormedAt}); this does not ask.
 */
export function compare(a: string, b: string): -1 | 0 | 1 {
  const length = Math.min(a.length, b.length);
  for (let at = 0; at < length; at++) {
    const x = a.charCodeAt(at);
    const y = b.charCodeAt(at);
    if (x !== y) {
      // Up to here the two are the same scalar values, so where they first differ each unit begins
      // a scalar value, or both are the second half of a pair. A surrogate begins one past U+FFFF,
      // above every unit from U+E000.
      return inScalarOrder(x) < inScalarOrder(y) ? -1 : 1;
    }
  }
  return a.length < b.length ? -1 : a.length > b.length ? 1 : 0;
}

/** A code unit moved so that the surrogates stand above U+E000 to U+FFFF and below nothing else. */
function inScalarOrder(unit: number): number {
  return unit < 0xD800 ? unit : unit < 0xE000 ? unit + 0x2000 : unit - 0x800;
}

function isLow(unit: number): boolean {
  return unit >= 0xDC00 && unit <= 0xDFFF;
}
