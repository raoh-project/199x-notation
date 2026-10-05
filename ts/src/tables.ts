// How the generated tables are looked up, and the bound the bounded rules take.

/**
 * The value of `cp` in a table of a value for each code point, written as two strings whose code
 * units are its values: the page of `cp`'s block, and `cp`'s place in it.
 */
export function valueAt(blocks: string, pages: string, cp: number): number {
  return pages.charCodeAt(blocks.charCodeAt(cp >> 8) << 8 | cp & 0xFF);
}

/** Where `cp` is in `from`, sorted, or -1 where it is not there. */
export function indexOf(from: readonly number[], cp: number): number {
  let low = 0;
  let high = from.length - 1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    const at = from[middle]!;
    if (at < cp) {
      low = middle + 1;
    } else if (at > cp) {
      high = middle - 1;
    } else {
      return middle;
    }
  }
  return -1;
}

/** How many scalar values `text` is made of, well formed as every table's text is. */
export function scalarsIn(text: string): number {
  let count = text.length;
  for (let at = 0; at < text.length; at++) {
    const unit = text.charCodeAt(at);
    if (unit >= 0xDC00 && unit <= 0xDFFF) {
      count--;
    }
  }
  return count;
}

/**
 * How far a scan of `s` from `at`, which is to go past no more than `room` code points and one
 * more, can go without counting them: a code point is a unit or two, so up to there it goes past no
 * more than that. A code point past the basic plane can leave it short, and the scan counts what it
 * went past and goes on.
 */
export function stop(s: string, at: number, room: number): number {
  return room < s.length - at ? at + room + 1 : s.length;
}

/**
 * Text written a code unit at a time into room that grows with it, and made a string once: an
 * answer built from many short pieces would otherwise be held as each of them until it is read.
 */
export class Written {
  #units = new Uint16Array(64);
  #length = 0;

  /** Writes the code units of `s` from `from` to `to`. */
  slice(s: string, from: number, to: number): void {
    this.#room(to - from);
    for (let at = from; at < to; at++) {
      this.#units[this.#length++] = s.charCodeAt(at);
    }
  }

  /** Writes the code units of `s`. */
  text(s: string): void {
    this.slice(s, 0, s.length);
  }

  /** Writes one code unit. */
  unit(unit: number): void {
    this.#room(1);
    this.#units[this.#length++] = unit;
  }

  /** Writes a scalar value, as two code units where it is past U+FFFF. */
  codePoint(cp: number): void {
    this.#room(2);
    if (cp > 0xFFFF) {
      this.#units[this.#length++] = 0xD800 + ((cp - 0x10000) >> 10);
      this.#units[this.#length++] = 0xDC00 + (cp & 0x3FF);
    } else {
      this.#units[this.#length++] = cp;
    }
  }

  /** What was written, made a slice at a time so that no call is handed more arguments than an
   *  engine takes. */
  toString(): string {
    let out = "";
    for (let at = 0; at < this.#length; at += 0x2000) {
      // apply reads the typed array as its arguments, where a spread would iterate it.
      out += String.fromCharCode.apply(null, this.#units.subarray(at, Math.min(at + 0x2000, this.#length)) as
        unknown as number[]);
    }
    return out;
  }

  #room(more: number): void {
    if (this.#length + more > this.#units.length) {
      const grown = new Uint16Array(Math.max(this.#units.length * 2, this.#length + more));
      grown.set(this.#units.subarray(0, this.#length));
      this.#units = grown;
    }
  }
}

/**
 * The bound a bounded rule was handed, checked: the most scalar values its answer may hold, an
 * integer a number holds exactly. A negative one is a bound even the empty text is longer than, and
 * answers nothing; anything else that is not such an integer is a mistake in the calling program.
 */
export function checkedBound(longest: number): number {
  if (!Number.isSafeInteger(longest)) {
    throw new RangeError(`${longest} is no bound: a bound is an integer a number holds exactly`);
  }
  return longest;
}
