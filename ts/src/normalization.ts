// Unicode 18.0.0 normalization, in the four forms of UAX #15: NFC, NFD, NFKC and NFKD.

import {
  CANONICAL_TO,
  COMBINING_CLASS_BLOCKS,
  COMBINING_CLASS_PAGES,
  COMPATIBILITY_TO,
  COMPOSITES,
  COMPOSITION_CELLS,
  COMPOSITION_COLUMNS,
  COMPOSITION_FIRST_BLOCKS,
  COMPOSITION_FIRST_PAGES,
  COMPOSITION_SECOND_BLOCKS,
  COMPOSITION_SECOND_PAGES,
  DECOMPOSITION_POSITION_BLOCKS,
  DECOMPOSITION_POSITION_PAGES,
  LONGEST_DECOMPOSITION,
  MOST_MARKS_COMPOSED,
  NFC_TRIVIAL_LIMIT,
  NFD_TRIVIAL_LIMIT,
  NFKC_TRIVIAL_LIMIT,
  NFKD_TRIVIAL_LIMIT,
  STABLE_BLOCKS,
  STABLE_PAGES,
} from "./normalization_tables.ts";
import { checkedBound, stop, valueAt, Written } from "./tables.ts";

/** The four normalization forms. */
export type Form =
  /** Canonical decomposition, then canonical composition. */
  | "NFC"
  /** Canonical decomposition. */
  | "NFD"
  /** Compatibility decomposition, then canonical composition. */
  | "NFKC"
  /** Compatibility decomposition. */
  | "NFKD";

/** What a form asks of the algorithm. */
interface FormFacts {
  readonly compatibility: boolean;
  readonly composes: boolean;
  /** Every code point below this is a stable starter of the form, known without asking a table. */
  readonly trivialLimit: number;
  /** The form's bit of STABLE. */
  readonly stableBit: number;
}

const FORMS: Readonly<Record<Form, FormFacts>> = {
  NFC: { compatibility: false, composes: true, trivialLimit: NFC_TRIVIAL_LIMIT, stableBit: 1 },
  NFD: { compatibility: false, composes: false, trivialLimit: NFD_TRIVIAL_LIMIT, stableBit: 2 },
  NFKC: { compatibility: true, composes: true, trivialLimit: NFKC_TRIVIAL_LIMIT, stableBit: 4 },
  NFKD: { compatibility: true, composes: false, trivialLimit: NFKD_TRIVIAL_LIMIT, stableBit: 8 },
};

/**
 * `text` in `form`.
 *
 * The algorithm is the standard three steps of UAX #15: decompose fully, with the Hangul
 * syllables' arithmetic decomposition, put combining marks in canonical order, and in the two
 * composing forms compose canonically wherever nothing blocks it, with Hangul's arithmetic
 * composition. The compatibility forms decompose by the compatibility mappings as well as the
 * canonical ones; composition is canonical in every form.
 *
 * Not `String.prototype.normalize`, which answers for the Unicode version of the engine, and leaves
 * a code point that version has not assigned as it is. `text` is well formed (see
 * {@link illFormedAt}); this does not ask, and half a surrogate pair handed in comes back out where it
 * was. A `form` that is none of the forms is a mistake in the calling program, and throws a
 * `TypeError`.
 */
export function normalize(form: Form, text: string): string {
  return within(factsOf(form), text, Number.POSITIVE_INFINITY)!;
}

/**
 * {@link normalize}, or `undefined` where the answer is longer than `longest` scalar values, which
 * is found out before that much is built. What it holds and how much of `text` it reads turn on
 * `longest` and not on the length of `text`: it reads no further once what it has read shows the
 * answer to be longer. `longest` is an integer a number holds exactly, or this throws a
 * `RangeError`.
 *
 * Text that is its own normalization is answered with itself. The text is read a code point at a
 * time and is kept as it is up to the first code point that is not a stable starter of the form: a
 * starter whose quick check for the form is Yes. From the stable starter before that one, which what
 * follows it may compose with, the algorithm is run up to the next stable starter, and the text is
 * kept as it is again from there. A stable starter composes with nothing before it and blocks every
 * mark after it from reaching a starter before it, so what comes before it is settled when it is
 * read.
 */
export function normalizeWithin(form: Form, text: string, longest: number): string | undefined {
  return within(factsOf(form), text, checkedBound(longest));
}

function factsOf(form: Form): FormFacts {
  if (!Object.hasOwn(FORMS, form)) {
    throw new TypeError(`${String(form)} is none of the normalization forms`);
  }
  return FORMS[form];
}

/**
 * {@link normalizeWithin}, the form checked.
 *
 * Two things are kept as the text is read: where the next run the algorithm goes over would begin,
 * the last stable starter read or, where none has been read since the last such run, where the text
 * is read to; and how many code points of the answer come before that. A run is written into the
 * answer only where it changed what it went over, and the answer is made only once one has: up to
 * there it is the text.
 *
 * Each stable starter read is at least one code point of the answer, as the starter it is or
 * composed with what follows it, so the text is read no further once those read and those before
 * them are more than `longest`.
 */
function within(form: FormFacts, s: string, longest: number): string | undefined {
  // Even the empty text is longer than a negative bound. From here, what is written and read is
  // never more than longest, so what is left of it is never negative.
  if (longest < 0) {
    return undefined;
  }
  // The answer up to kept, where a run has changed what it went over.
  let out: Written | undefined;
  let kept = 0;
  let start = 0;
  // The code points of the answer before the last run the algorithm went over, and the stable
  // starters read since, the last of them at start where start is before at.
  let before = 0;
  let read = 0;
  let composing: Composing | undefined;
  const stable: Scan = { end: 0, count: 0, last: 0 };
  for (let at = 0; at < s.length;) {
    stableUpTo(stable, form, s, at, longest - before - read);
    if (stable.end > at) {
      read += stable.count;
      if (before + read > longest) {
        return undefined;
      }
      start = stable.last;
      at = stable.end;
      if (at === s.length) {
        break;
      }
    }
    if (start < at) {
      read--;
    }
    composing ??= new Composing(form, longest);
    const end = composing.run(s, start, at, before + read);
    if (end < 0) {
      return undefined;
    }
    if (!composing.wrote(s, start, end)) {
      out ??= new Written();
      out.slice(s, kept, start);
      for (const cp of composing.out) {
        out.codePoint(cp);
      }
      kept = end;
    }
    before = composing.written;
    read = 0;
    start = end;
    at = end;
  }
  if (out === undefined) {
    return s;
  }
  out.slice(s, kept, s.length);
  return out.toString();
}

/** What a scan over a run of stable starters learns: where the run ends, how many code points it
 *  holds, and where the last of them begins. */
interface Scan {
  end: number;
  count: number;
  last: number;
}

/**
 * Writes into `scan` where the stable starters of `form` that `s` has from `at` end, the first code
 * point from there that is not one or the end of the text, or where it has gone past more than
 * `room` of them; how many code points it went past; and where the last of them begins. One scan is
 * written again for each run, so that reading a text makes no object for each.
 */
function stableUpTo(scan: Scan, form: FormFacts, s: string, at: number, room: number): void {
  const limit = form.trivialLimit;
  const bit = form.stableBit;
  const from = at;
  let pairs = 0;
  let last = at;
  while (at < s.length && at - from - pairs <= room) {
    const end = stop(s, at, room - (at - from - pairs));
    while (at < end) {
      // Every form's trivial limit is below the surrogates, which the generator checks, so a unit
      // below it is a code point.
      if (s.charCodeAt(at) < limit) {
        last = at;
        at++;
        continue;
      }
      const cp = s.codePointAt(at)!;
      if (!isStable(bit, cp)) {
        scan.end = at;
        scan.count = at - from - pairs;
        scan.last = last;
        return;
      }
      last = at;
      if (cp > 0xFFFF) {
        pairs++;
        at += 2;
      } else {
        at++;
      }
    }
  }
  scan.end = at;
  scan.count = at - from - pairs;
  scan.last = last;
}

/** Whether `cp` is a stable starter of the form whose bit of STABLE is `bit`. */
function isStable(bit: number, cp: number): boolean {
  return (valueAt(STABLE_BLOCKS, STABLE_PAGES, cp) & bit) !== 0;
}

/** How many marks a run may hold before they are put in order by counting rather than by insertion,
 *  which is quadratic in the run. */
const FEW_MARKS = 32;

/**
 * One pass of canonical ordering and, where the form composes, composition over code points
 * already decomposed, holding the starter of the run it is in and the marks after it, and writing
 * what is settled.
 *
 * What is written and the least of the answer what is held can come to are no more than `longest`,
 * which `take` holds to before it holds another mark: the starter, and the marks but those it may
 * compose with, which are no more than MOST_MARKS_COMPOSED. So a run holds no more than `longest`
 * and that many marks, whatever the length of the text.
 */
class Composing {
  readonly #compatibility: boolean;
  readonly #composes: boolean;
  readonly #stableBit: number;
  readonly #longest: number;
  readonly #parts: number[] = new Array<number>(LONGEST_DECOMPOSITION).fill(0);
  /** What `run` wrote, the run settled, as code points. */
  readonly out: number[] = [];
  /** How many code points of the answer come before what is held: those before the run, and those
   *  it has written. */
  written = 0;
  #starter = -1;
  #marks: number[] = [];

  constructor(form: FormFacts, longest: number) {
    this.#compatibility = form.compatibility;
    this.#composes = form.composes;
    this.#stableBit = form.stableBit;
    this.#longest = longest;
  }

  /**
   * Runs the algorithm over `s` from `start`, `before` code points of the answer coming before it,
   * up to the first stable starter after `at`, or to the end of the text where none comes, and
   * writes the run settled into `out`. The code points from `start` to `at` are taken, and so is
   * the one there, whatever they are; the caller has read them. Answers where it stopped, or -1
   * where the answer has passed `longest`.
   */
  run(s: string, start: number, at: number, before: number): number {
    this.out.length = 0;
    this.written = before;
    let j = start;
    while (j < s.length) {
      const cp = s.codePointAt(j)!;
      // What is before at has been read by the caller; the stable starter that ends the run is
      // read by the caller next.
      if (j > at && isStable(this.#stableBit, cp)) {
        break;
      }
      j += cp > 0xFFFF ? 2 : 1;
      const length = decomposeInto(this.#parts, cp, this.#compatibility);
      if (length === 0) {
        if (!this.#take(cp)) {
          return -1;
        }
        continue;
      }
      for (let i = 0; i < length; i++) {
        if (!this.#take(this.#parts[i]!)) {
          return -1;
        }
      }
    }
    return this.#write(this.#settle()) ? j : -1;
  }

  /** Whether what `run` wrote is what it went over, from `start` to `end` of `s`. */
  wrote(s: string, start: number, end: number): boolean {
    let at = start;
    for (const cp of this.out) {
      if (at >= end || s.codePointAt(at) !== cp) {
        return false;
      }
      at += cp > 0xFFFF ? 2 : 1;
    }
    return at === end;
  }

  /** Takes the next decomposed code point; false where what is written has passed `longest`. */
  #take(cp: number): boolean {
    if (combiningClass(cp) !== 0) {
      if (this.#leastHeld(this.#marks.length + 1) > this.#longest - this.written) {
        return false;
      }
      this.#marks.push(cp);
      return true;
    }
    const kept = this.#settle();
    if (this.#composes && this.#starter >= 0 && kept === 0) {
      const composed = compose(this.#starter, cp);
      if (composed >= 0) {
        this.#starter = composed;
        return true;
      }
    }
    if (!this.#write(kept)) {
      return false;
    }
    this.#starter = cp;
    return true;
  }

  /**
   * The least number of code points of the answer the starter held and `marks` marks after it come
   * to, whatever follows: every mark, and the starter, but those of the marks it may compose with in
   * a composing form.
   */
  #leastHeld(marks: number): number {
    if (this.#starter < 0) {
      return marks;
    }
    return 1 + (this.#composes ? Math.max(marks - MOST_MARKS_COMPOSED, 0) : marks);
  }

  /** Puts the held marks in canonical order and, where the form composes, composes into the starter
   *  each one nothing blocks; answers how many marks are left after the starter. */
  #settle(): number {
    if (this.#marks.length > 1) {
      this.#order();
    }
    if (!this.#composes || this.#starter < 0) {
      return this.#marks.length;
    }
    const marks = this.#marks;
    let kept = 0;
    let lastClass = -1;
    for (let i = 0; i < marks.length; i++) {
      const mark = marks[i]!;
      const markClass = combiningClass(mark);
      const composed = lastClass < markClass ? compose(this.#starter, mark) : -1;
      if (composed >= 0) {
        this.#starter = composed;
      } else {
        marks[kept++] = mark;
        lastClass = markClass;
      }
    }
    marks.length = kept;
    return kept;
  }

  /** Canonical ordering of the held marks: stable, by combining class. */
  #order(): void {
    const marks = this.#marks;
    if (marks.length <= FEW_MARKS) {
      for (let i = 1; i < marks.length; i++) {
        const mark = marks[i]!;
        const markClass = combiningClass(mark);
        let j = i;
        while (j > 0 && combiningClass(marks[j - 1]!) > markClass) {
          marks[j] = marks[j - 1]!;
          j--;
        }
        marks[j] = mark;
      }
      return;
    }
    const starts = new Array<number>(257).fill(0);
    for (const mark of marks) {
      starts[combiningClass(mark) + 1]!++;
    }
    for (let c = 1; c < starts.length; c++) {
      starts[c]! += starts[c - 1]!;
    }
    const ordered = new Array<number>(marks.length);
    for (const mark of marks) {
      ordered[starts[combiningClass(mark)]!++] = mark;
    }
    this.#marks = ordered;
  }

  /** Writes the starter and the `kept` marks after it, and empties the run. */
  #write(kept: number): boolean {
    if (this.#starter >= 0 && !this.#writeOne(this.#starter)) {
      return false;
    }
    for (let i = 0; i < kept; i++) {
      if (!this.#writeOne(this.#marks[i]!)) {
        return false;
      }
    }
    this.#starter = -1;
    this.#marks.length = 0;
    return true;
  }

  #writeOne(cp: number): boolean {
    if (this.written >= this.#longest) {
      return false;
    }
    this.out.push(cp);
    this.written++;
    return true;
  }
}

// Hangul's arithmetic decomposition and composition, as UAX #15 states them.

const S_BASE = 0xAC00;
const L_BASE = 0x1100;
const V_BASE = 0x1161;
const T_BASE = 0x11A7;
const L_COUNT = 19;
const V_COUNT = 21;
const T_COUNT = 28;
const N_COUNT = V_COUNT * T_COUNT;
const S_COUNT = L_COUNT * N_COUNT;

function isHangulSyllable(cp: number): boolean {
  return cp >= S_BASE && cp < S_BASE + S_COUNT;
}

/** Writes `cp`'s full decomposition into `parts`, which has room for the longest, and answers how
 *  many code points it is, or 0 where `cp` is its own. */
function decomposeInto(parts: number[], cp: number, compatibility: boolean): number {
  if (isHangulSyllable(cp)) {
    const sIndex = cp - S_BASE;
    parts[0] = L_BASE + Math.floor(sIndex / N_COUNT);
    parts[1] = V_BASE + Math.floor((sIndex % N_COUNT) / T_COUNT);
    const t = T_BASE + sIndex % T_COUNT;
    if (t === T_BASE) {
      return 2;
    }
    parts[2] = t;
    return 3;
  }
  return decomposeTables(parts, 0, cp, compatibility);
}

/** Writes `cp`'s decomposition by the tables, followed as far as they go, into `parts` from `at`,
 *  and answers how many code points it is, or 0 where the tables name no decomposition of `cp`. */
function decomposeTables(parts: number[], at: number, cp: number, compatibility: boolean): number {
  const mapped = decomposition(cp, compatibility);
  if (mapped === undefined) {
    return 0;
  }
  let length = 0;
  for (let i = 0; i < mapped.length;) {
    const part = mapped.codePointAt(i)!;
    i += part > 0xFFFF ? 2 : 1;
    let further = decomposeTables(parts, at + length, part, compatibility);
    if (further === 0) {
      parts[at + length] = part;
      further = 1;
    }
    length += further;
  }
  return length;
}

/** `cp`'s one-step decomposition by CANONICAL, or with `compatibility` by COMPATIBILITY as well,
 *  read where DECOMPOSITION_POSITION says it is; `undefined` where it has none. */
function decomposition(cp: number, compatibility: boolean): string | undefined {
  const at = valueAt(DECOMPOSITION_POSITION_BLOCKS, DECOMPOSITION_POSITION_PAGES, cp);
  if (at === 0) {
    return undefined;
  }
  if (at <= CANONICAL_TO.length) {
    return CANONICAL_TO[at - 1];
  }
  return compatibility ? COMPATIBILITY_TO[at - CANONICAL_TO.length - 1] : undefined;
}

/** The canonical combining class of `cp`: 0 for a starter, and for a mark the class canonical
 *  ordering sorts it by. */
function combiningClass(cp: number): number {
  return valueAt(COMBINING_CLASS_BLOCKS, COMBINING_CLASS_PAGES, cp);
}

/**
 * The primary composite of `starter` followed by `cp`, or -1 where the pair does not compose:
 * Hangul's arithmetic L+V and LV+T composition, or COMPOSITION_CELLS at the row of `starter` and the
 * column of `cp`.
 */
function compose(starter: number, cp: number): number {
  if (starter >= L_BASE && starter < L_BASE + L_COUNT && cp >= V_BASE && cp < V_BASE + V_COUNT) {
    return S_BASE + ((starter - L_BASE) * V_COUNT + (cp - V_BASE)) * T_COUNT;
  }
  if (isHangulSyllable(starter) && (starter - S_BASE) % T_COUNT === 0 && cp > T_BASE && cp < T_BASE + T_COUNT) {
    return starter + (cp - T_BASE);
  }
  const row = valueAt(COMPOSITION_FIRST_BLOCKS, COMPOSITION_FIRST_PAGES, starter);
  const column = valueAt(COMPOSITION_SECOND_BLOCKS, COMPOSITION_SECOND_PAGES, cp);
  if (row === 0 || column === 0) {
    return -1;
  }
  const at = COMPOSITION_CELLS.charCodeAt((row - 1) * COMPOSITION_COLUMNS + column - 1);
  return at === 0 ? -1 : COMPOSITES[at - 1]!;
}
