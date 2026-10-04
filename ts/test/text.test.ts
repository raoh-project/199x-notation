// What only a JavaScript string can hold: half of a surrogate pair, which the suite has no text
// for, since no sequence of scalar values holds one.

import assert from "node:assert/strict";
import { it } from "node:test";
import { compare, illFormedAt, scalarCount } from "../src/index.ts";

it("a string is well formed where every surrogate is half of a pair", () => {
  assert.equal(illFormedAt(""), -1);
  assert.equal(illFormedAt("a\u{1F600}b"), -1);
  assert.equal(illFormedAt("a\uD83D"), 1);
  assert.equal(illFormedAt("\uDE00a"), 0);
  assert.equal(illFormedAt("\u{1F600}\uDE00\uD83D"), 2);
  assert.equal(illFormedAt("\uD83D😀"), 0);
});

it("a scalar value past U+FFFF counts once", () => {
  assert.equal(scalarCount("\u{10FFFF}\u{10FFFF}"), 2);
  assert.equal(scalarCount("e\u0301"), 2);
});

it("text is ordered by scalar value wherever the two first differ", () => {
  assert.equal(compare("\uFFFF", "\u{10000}"), -1);
  assert.equal(compare("x\uE000", "x\u{10000}"), -1);
  assert.equal(compare("x\u{10000}", "x\u{10001}"), -1);
  assert.equal(compare("x\u{1F600}", "x\u{1F600}"), 0);
  assert.equal(compare("\uD7FF", "\u{10000}"), -1);
  assert.equal(compare("\u{10000}a", "\u{10000}"), 1);
});
