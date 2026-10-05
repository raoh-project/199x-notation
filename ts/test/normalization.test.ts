// Normalization held to Unicode's own NormalizationTest.txt in full, and to suite/normalization-bound.txt
// in a bound.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { it } from "node:test";
import { type Form, normalize, normalizeWithin } from "../src/index.ts";
import { eachLine, repositoryFile, shown } from "./suite.ts";

const FORMS: readonly Form[] = ["NFC", "NFD", "NFKC", "NFKD"];

function decodeHex(field: string): string {
  return String.fromCodePoint(...field.trim().split(/ +/).filter((each) => each !== "")
    .map((hex) => Number.parseInt(hex, 16)));
}

// Each data line is five columns, source, NFC, NFD, NFKC and NFKD, and the file states what
// conformance is: c2 == toNFC(c1) == toNFC(c2) == toNFC(c3) and c4 == toNFC(c4) == toNFC(c5);
// c3 == toNFD(c1) == toNFD(c2) == toNFD(c3) and c5 == toNFD(c4) == toNFD(c5); c4 == toNFKC and
// c5 == toNFKD of all five. A code point no line of Part 1 names is its own normalization in every
// form.
it("normalization answers every line of the conformance test", (t) => {
  const path = repositoryFile("ucd/18.0.0/NormalizationTest.txt", t);
  if (path === undefined) {
    return;
  }
  const data = readFileSync(path, "utf-8");
  assert.ok(data.startsWith("# NormalizationTest-18.0.0.txt\n"), "the file opens with another line");
  const named = new Uint8Array(0x110000);
  let partOne = false;
  let checked = 0;
  const failures: string[] = [];
  data.split("\n").forEach((line, n) => {
    const text = line.split("#")[0]!.trim();
    if (text.startsWith("@")) {
      partOne = text === "@Part1";
      return;
    }
    if (text === "") {
      return;
    }
    const columns = text.split(";").slice(0, 5).map(decodeHex);
    if (partOne) {
      named[columns[0]!.codePointAt(0)!] = 1;
    }
    checked++;
    columns.forEach((source, i) => {
      const [nfc, nfd] = i < 3 ? [columns[1]!, columns[2]!] : [columns[3]!, columns[4]!];
      for (const [form, want] of [["NFC", nfc], ["NFD", nfd], ["NFKC", columns[3]!], ["NFKD", columns[4]!]] as const) {
        const got = normalize(form, source);
        if (got !== want) {
          failures.push(`line ${n + 1}: ${form}(c${i + 1} ${shown(source)}) is ${shown(got)}, not ${shown(want)}`);
        }
      }
    });
  });
  assert.ok(checked >= 10_000, `the file's data lines were read: ${checked}`);
  for (let cp = 0; cp <= 0x10FFFF; cp++) {
    if ((cp >= 0xD800 && cp <= 0xDFFF) || named[cp] === 1) {
      continue;
    }
    const alone = String.fromCodePoint(cp);
    for (const form of FORMS) {
      if (normalize(form, alone) !== alone) {
        failures.push(`${form} changes U+${cp.toString(16).toUpperCase()}, which Part 1 does not name`);
      }
    }
  }
  assert.deepEqual(failures.slice(0, 20), [], `${failures.length} failed`);
});

it("normalization within a bound answers every line of the suite", (t) => {
  eachLine(t, "suite/normalization-bound.txt", 5, (line) => {
    const text = line.text(0);
    const form = line.oneOf(1, ["NFC", "NFD", "NFKC", "NFKD"]);
    const bound = line.number(2);
    const past = line.oneOf(3, ["NORMALIZED", "PAST"]) === "PAST";
    let normalized: string | undefined;
    if (past) {
      line.empty(4);
    } else {
      normalized = line.text(4);
    }
    const answer = normalizeWithin(form, text, bound);
    return answer === normalized ? undefined
      : `${shown(text)} in ${form} within ${bound} is ${answer === undefined ? "past" : shown(answer)}`;
  });
});

// A text normalized one combining run at a time is the text normalized whole: a bound that is the
// answer's length takes it, and one less does not.
it("a bound is held on the answer", () => {
  for (const text of ["", "a", "Å", "Ǻ", "ﬃ", "㌀", "가", "\u{1D15E}", "ȩ\u0301\u0301x", "\u{1D15E}\u{1D165}"]) {
    for (const form of FORMS) {
      const whole = normalize(form, text);
      const length = [...whole].length;
      assert.equal(normalizeWithin(form, text, length), whole, `${form} of ${shown(text)}`);
      if (length > 0) {
        assert.equal(normalizeWithin(form, text, length - 1), undefined, `${form} of ${shown(text)}`);
      }
    }
  }
});

// More marks after one starter than are put in order by insertion, of two classes, U+0316 and
// U+0323 at 220 and U+0301 and U+0302 at 230: put in order by counting, which keeps the marks of
// each class in the order they came.
it("a long combining run is put in canonical order, stably", () => {
  const marks = [..."\u0301\u0323\u0302\u0316".repeat(20)];
  const below = marks.filter((m) => m === "\u0323" || m === "\u0316").join("");
  const above = marks.filter((m) => m === "\u0301" || m === "\u0302").join("");
  assert.equal(normalize("NFD", `a${marks.join("")}`), `a${below}${above}`);
});

it("a form and a bound are what the caller is held to", () => {
  assert.throws(() => normalize("NFX" as Form, "a"), TypeError);
  assert.throws(() => normalizeWithin("NFC", "a", 0.5), RangeError);
  assert.equal(normalizeWithin("NFC", "", -1), undefined);
  // Half a surrogate pair comes back out where it was.
  assert.equal(normalize("NFC", "a\uD800\u0301"), "a\uD800\u0301");
});
