// Case conversion held to suite/case.txt, and the tables it reads held to the database by a reading
// of their own, as the Rust implementation's tests hold its tables.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { it } from "node:test";
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
} from "../src/case_tables.ts";
import { lowercase, lowercaseWithin, uppercase, uppercaseWithin } from "../src/index.ts";
import { indexOf, valueAt } from "../src/tables.ts";
import { eachLine, repositoryFile, shown } from "./suite.ts";

it("case conversion answers every line of the suite", (t) => {
  eachLine(t, "suite/case.txt", 5, (line) => {
    const text = line.text(0);
    const lower = line.oneOf(1, ["LOWER", "UPPER"]) === "LOWER";
    const bound = line.numberOrNothing(2);
    const past = line.oneOf(3, ["MAPPED", "PAST"]) === "PAST";
    let mapped: string | undefined;
    if (past) {
      line.empty(4);
    } else {
      mapped = line.text(4);
    }
    if (bound === undefined && past) {
      return "a conversion without a bound is never past one";
    }
    const answer = bound === undefined
      ? (lower ? lowercase(text) : uppercase(text))
      : (lower ? lowercaseWithin(text, bound) : uppercaseWithin(text, bound));
    return answer === mapped ? undefined
      : `${shown(text)} is ${answer === undefined ? "past" : shown(answer)}, not ${mapped === undefined ? "past" : shown(mapped)}`;
  });
});

const CASED = 1;
const CASE_IGNORABLE = 2;
const FINAL_SIGMA_NAMED = 4;

function hasContext(cp: number, bit: number): boolean {
  return (valueAt(CASE_CONTEXT_BLOCKS, CASE_CONTEXT_PAGES, cp) & bit) !== 0;
}

function* scalarValues(): Generator<number> {
  for (let cp = 0; cp <= 0x10FFFF; cp++) {
    if (cp < 0xD800 || cp > 0xDFFF) {
      yield cp;
    }
  }
}

// Each position table answers 0 where the mapping has nothing for a code point or maps it to
// itself, and otherwise one more than where the mapping holds it; and no code point FINAL_SIGMA
// names is 0.
it("a position table answers where the mapping holds what changes", () => {
  for (const [blocks, pages, from, to] of [
    [LOWER_POSITION_BLOCKS, LOWER_POSITION_PAGES, LOWER_FROM, LOWER_TO],
    [UPPER_POSITION_BLOCKS, UPPER_POSITION_PAGES, UPPER_FROM, UPPER_TO],
  ] as const) {
    for (const cp of scalarValues()) {
      const at = indexOf(from, cp);
      const expected = at >= 0 && to[at] !== String.fromCodePoint(cp) ? at + 1 : 0;
      if (valueAt(blocks, pages, cp) !== expected) {
        assert.fail(`U+${cp.toString(16)} is at ${valueAt(blocks, pages, cp)}, not ${expected}`);
      }
    }
  }
  for (const cp of FINAL_SIGMA_FROM) {
    assert.notEqual(valueAt(LOWER_POSITION_BLOCKS, LOWER_POSITION_PAGES, cp), 0);
  }
});

/** For each code point, whether a property file's `text` gives it `name`. */
function property(text: string, name: string): Uint8Array {
  const has = new Uint8Array(0x110000);
  for (const line of text.split("\n")) {
    const fields = line.split("#")[0]!.split(";");
    if (fields.length < 2 || fields[1]!.trim() !== name) {
      continue;
    }
    const [first, last] = fields[0]!.trim().split("..").map((hex) => Number.parseInt(hex, 16));
    for (let cp = first!; cp <= (last ?? first!); cp++) {
      has[cp] = 1;
    }
  }
  assert.ok(has.includes(1), `no code point is ${name}`);
  return has;
}

it("the case context is what the database states", (t) => {
  const path = repositoryFile("ucd/18.0.0/DerivedCoreProperties.txt", t);
  if (path === undefined) {
    return;
  }
  const text = readFileSync(path, "utf-8");
  const cased = property(text, "Cased");
  const ignorable = property(text, "Case_Ignorable");
  for (const cp of scalarValues()) {
    if (hasContext(cp, CASED) !== (cased[cp] === 1)
      || hasContext(cp, CASE_IGNORABLE) !== (ignorable[cp] === 1)
      || hasContext(cp, FINAL_SIGMA_NAMED) !== (indexOf(FINAL_SIGMA_FROM, cp) >= 0)) {
      assert.fail(`the case context of U+${cp.toString(16)} is not what the database states`);
    }
  }
});

/** Final_Sigma as Java decides it: from each sigma, look back and then forward past the
 *  Case_Ignorable code points. */
function lowercaseByLookingAround(text: readonly number[]): string {
  let out = "";
  text.forEach((cp, at) => {
    const before = text.slice(0, at).reverse().find((c) => !hasContext(c, CASE_IGNORABLE));
    const after = text.slice(at + 1).find((c) => !hasContext(c, CASE_IGNORABLE));
    const final = before !== undefined && hasContext(before, CASED)
      && !(after !== undefined && hasContext(after, CASED));
    const sigma = indexOf(FINAL_SIGMA_FROM, cp);
    const lower = indexOf(LOWER_FROM, cp);
    out += final && sigma >= 0 ? FINAL_SIGMA_TO[sigma]
      : lower >= 0 ? LOWER_TO[lower] : String.fromCodePoint(cp);
  });
  return out;
}

// Every text of up to six code points over a cased letter, a sigma, a Case_Ignorable code point,
// one that is both Cased and Case_Ignorable, one that is neither, and one that maps to two,
// lowercased and by mapping each code point and looking around each sigma, alike and within every
// bound.
it("lowercase is the mapping with Final_Sigma looked for around each sigma", () => {
  const alphabet = [0x41, 0x3A3, 0x2E, 0x2B0, 0x20, 0x130];
  assert.ok(hasContext(0x2B0, CASED) && hasContext(0x2B0, CASE_IGNORABLE));
  for (let length = 0; length <= 6; length++) {
    for (let n = 0; n < alphabet.length ** length; n++) {
      const text: number[] = [];
      for (let rest = n, i = 0; i < length; i++, rest = Math.floor(rest / alphabet.length)) {
        text.push(alphabet[rest % alphabet.length]!);
      }
      const s = String.fromCodePoint(...text);
      const expected = lowercaseByLookingAround(text);
      assert.equal(lowercase(s), expected, shown(s));
      const scalars = [...expected].length;
      for (let bound = 0; bound <= scalars + 1; bound++) {
        assert.equal(lowercaseWithin(s, bound), bound >= scalars ? expected : undefined, `${shown(s)} within ${bound}`);
      }
    }
  }
});

it("a scalar value past U+FFFF is mapped and counted as one", () => {
  // U+10400 DESERET CAPITAL LONG I lowercases to U+10428.
  assert.equal(lowercase("a\u{10400}B"), "a\u{10428}b");
  assert.equal(uppercase("\u{10428}"), "\u{10400}");
  assert.equal(lowercaseWithin("\u{10400}\u{10400}", 2), "\u{10428}\u{10428}");
  assert.equal(lowercaseWithin("\u{10400}\u{10400}", 1), undefined);
  assert.equal(uppercaseWithin("\u{1F600}\u{1F600}x", 2), undefined);
  assert.equal(uppercaseWithin("\u{1F600}\u{1F600}x", 3), "\u{1F600}\u{1F600}X");
});

it("text that maps to itself is answered as it is, and a bound is an integer", () => {
  const same = "already lowercase \u{1F600}";
  assert.equal(lowercase(same), same);
  assert.equal(lowercaseWithin("", -1), undefined);
  assert.throws(() => lowercaseWithin("a", 1.5), RangeError);
  assert.throws(() => uppercaseWithin("a", Number.NaN), RangeError);
  assert.throws(() => uppercaseWithin("a", Number.POSITIVE_INFINITY), RangeError);
});
