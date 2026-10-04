// The implementation held to every line of the repository's `suite` directory that its rules
// answer.

import assert from "node:assert/strict";
import { it } from "node:test";
import {
  checkTemporal,
  compare,
  isWhiteSpace,
  readDate,
  readDateTime,
  readInstant,
  readOffsetDateTime,
  readTime,
  scalarCount,
  type TemporalKind,
} from "../src/index.ts";
import { eachLine, shown } from "./suite.ts";

it("white space is the set the suite lists", (t) => {
  const listed = new Set<number>();
  if (eachLine(t, "suite/white-space.txt", 1, (line) => {
    listed.add(line.scalar(0));
    return undefined;
  }) === 0) {
    return;
  }
  const wrong: string[] = [];
  for (let cp = 0; cp <= 0x10FFFF; cp++) {
    if (cp >= 0xD800 && cp <= 0xDFFF) {
      continue;
    }
    if (isWhiteSpace(cp) !== listed.has(cp)) {
      wrong.push(`U+${cp.toString(16).toUpperCase()} is white space: ${isWhiteSpace(cp)}, and the suite lists it: ${listed.has(cp)}`);
    }
  }
  assert.deepEqual(wrong, []);
});

it("scalar length answers every line of the suite", (t) => {
  eachLine(t, "suite/scalar-length.txt", 2, (line) => {
    const text = line.text(0);
    const length = line.number(1);
    return scalarCount(text) === length ? undefined : `${shown(text)} is ${scalarCount(text)} long, not ${length}`;
  });
});

it("scalar order answers every line of the suite", (t) => {
  eachLine(t, "suite/scalar-order.txt", 3, (line) => {
    const a = line.text(0);
    const b = line.text(1);
    const order = { LESS: -1, EQUAL: 0, GREATER: 1 }[line.oneOf(2, ["LESS", "EQUAL", "GREATER"])];
    return compare(a, b) === order && compare(b, a) === -order
      ? undefined
      : `${shown(a)} against ${shown(b)} is ${compare(a, b)}, not ${order}`;
  });
});

const KINDS = {
  DATE: "date",
  TIME: "time",
  DATETIME: "dateTime",
  OFFSET_DATETIME: "offsetDateTime",
  INSTANT: "instant",
} as const satisfies Record<string, TemporalKind>;

const KIND_NAMES = Object.keys(KINDS) as (keyof typeof KINDS)[];

it("temporal text answers every line of the suite", (t) => {
  eachLine(t, "suite/temporal.txt", 3, (line) => {
    const kind = KINDS[line.oneOf(0, KIND_NAMES)];
    const text = line.text(1);
    const admitted = line.oneOf(2, ["ADMITTED", "REFUSED"]) === "ADMITTED";
    const answer = checkTemporal(kind, text);
    return (answer === "admitted") === admitted ? undefined : `${shown(text)} as ${kind} is ${answer}`;
  });
});

// The readers are this package's own, so the suite holds no value they give; on every line, the
// reader of the kind admits what checkTemporal admits and refuses with the same refusal.
const READERS = {
  date: readDate,
  time: readTime,
  dateTime: readDateTime,
  offsetDateTime: readOffsetDateTime,
  instant: readInstant,
} as const;

it("temporal readers admit what the check admits on every line of the suite", (t) => {
  eachLine(t, "suite/temporal.txt", 3, (line) => {
    const kind = KINDS[line.oneOf(0, KIND_NAMES)];
    const text = line.text(1);
    line.oneOf(2, ["ADMITTED", "REFUSED"]);
    const read = READERS[kind](text);
    const answer = "value" in read ? "admitted" : read.refusal;
    const checked = checkTemporal(kind, text);
    return answer === checked ? undefined : `${shown(text)} as ${kind} reads ${answer}, checks ${checked}`;
  });
});

