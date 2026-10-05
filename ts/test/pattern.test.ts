// The pattern language held to every line of the suite's pattern files, and what only this
// implementation answers: where a refusal points, what a walk keeps, and what only a JavaScript
// string can hold.

import assert from "node:assert/strict";
import { it } from "node:test";
import {
  PATTERN_LIMITS,
  type PatternLimit,
  type PatternRead,
  type PatternRefusal,
  type PatternRefused,
  readPattern,
} from "../src/index.ts";
import { build } from "../src/pattern_machine.ts";
import { readText } from "../src/pattern_read.ts";
import { Walk } from "../src/pattern_walk.ts";
import { eachLine, shown } from "./suite.ts";

const LIMITS = {
  REPETITION_COUNT: "repetitionCount",
  NESTING_DEPTH: "nestingDepth",
  MACHINE_STATES: "machineStates",
} as const satisfies Record<string, PatternLimit>;

function described(read: PatternRead): string {
  return "pattern" in read ? "a pattern" : JSON.stringify(read);
}

it("patterns are read as every line of the suite says", (t) => {
  eachLine(t, "suite/pattern-read.txt", 3, (line) => {
    const pattern = line.text(0);
    const outcome = line.oneOf(1, ["READ", "REFUSED", "BEYOND"]);
    let limit: PatternLimit | undefined;
    if (outcome === "BEYOND") {
      const named = line.oneOfOrNothing(2, ["REPETITION_COUNT", "NESTING_DEPTH", "MACHINE_STATES"]);
      limit = named === undefined ? undefined : LIMITS[named];
    } else {
      line.empty(2);
    }
    const read = readPattern(pattern);
    const asSaid = "pattern" in read ? outcome === "READ"
      : "refused" in read ? outcome === "REFUSED"
      : outcome === "BEYOND" && (limit === undefined || read.beyond.limit === limit);
    return asSaid ? undefined : `${shown(pattern)} is ${described(read)}, not ${outcome} ${limit ?? ""}`;
  });
});

it("pattern states are counted as every line of the suite says", (t) => {
  // The states the specifications state the limit in, which the file's rule is written against.
  const most = 250_000;
  eachLine(t, "suite/pattern-states.txt", 2, (line) => {
    const pattern = line.text(0);
    const states = line.number(1);
    const at = readPattern(`(?:${pattern})|a{0,${most - 5 - states}}`);
    const past = readPattern(`(?:${pattern})|a{0,${most - 4 - states}}`);
    if (!("pattern" in at)) {
      return `${shown(pattern)} at the limit is ${described(at)}`;
    }
    if (!("beyond" in past && past.beyond.limit === "machineStates")) {
      return `${shown(pattern)} past the limit is ${described(past)}`;
    }
    return undefined;
  });
});

it("patterns accept what every line of the suite says", (t) => {
  eachLine(t, "suite/pattern-match.txt", 3, (line) => {
    const pattern = line.text(0);
    const subject = line.text(1);
    const accepted = line.yesOrNo(2);
    const read = readPattern(pattern);
    if (!("pattern" in read)) {
      return `${shown(pattern)} is not read: ${described(read)}`;
    }
    return read.pattern.matches(subject) === accepted ? undefined
      : `${shown(pattern)} accepts ${shown(subject)}: ${!accepted}`;
  });
});

function refusedOf(pattern: string): PatternRefused {
  const read = readPattern(pattern);
  assert.ok("refused" in read, `${JSON.stringify(pattern)} is ${described(read)}, not refused`);
  return read.refused;
}

function patternOf(pattern: string) {
  const read = readPattern(pattern);
  assert.ok("pattern" in read, `${JSON.stringify(pattern)} is ${described(read)}`);
  return read.pattern;
}

it("each refusal is for what was written", () => {
  const cases: Record<string, PatternRefusal> = {
    "(a": "somethingUnclosed",
    "[a": "somethingUnclosed",
    "a)": "somethingUnclosed",
    "*a": "somethingUnclosed",
    "a{6,2}": "aCountThisCannotRead",
    "a{": "aCountThisCannotRead",
    "[b-a]": "aCountThisCannotRead",
    "\\y": "anEscapeThisDoesNotRead",
    "\\x{110000}": "anEscapeThisDoesNotRead",
    "\\\uA7DD": "anEscapeThisDoesNotRead",
    "(?=a)b": "aGroupTheGrammarDoesNotHave",
    "(?<name>a)": "aGroupTheGrammarDoesNotHave",
    "(?i)a": "aGroupTheGrammarDoesNotHave",
    "(a)\\1": "aBackReference",
    "\\k<a>": "aBackReference",
    "\\p{Alpha}": "aCharacterProperty",
    "\\bword\\b": "aBoundary",
    "\\Qa+b\\E": "aQuotation",
    "[a-z&&[^bc]]": "aClassOfClasses",
    "[a[bc]]": "aClassOfClasses",
    "a{2,6}+": "aPossessiveRepetition",
    "(?:|a)++": "aPossessiveRepetition",
    "(a|)^b": "anAnchorThisCannotPlace",
    "(^a)*": "anAnchorThisCannotPlace",
    "a$b": "anAnchorThisCannotPlace",
  };
  for (const [pattern, why] of Object.entries(cases)) {
    assert.equal(refusedOf(pattern).why, why, pattern);
  }
});

// A pair of \u escapes is the one character it encodes, worked out before the character is asked
// whether it is a symbol; half of a pair is no symbol however it is written, in a class or out of
// one.
it("a pair of unicode escapes is one character", () => {
  for (const pattern of ["\\uD83D\\uDE00", "[\\uD83D\\uDE00]", "[\\uD83D\\uDE00-\\uD83D\\uDE4F]", "\\x{1F600}", "\u{1F600}"]) {
    const read = patternOf(pattern);
    assert.ok(read.matches("\u{1F600}"), pattern);
    assert.ok(!read.matches("\u{1F601}\u{1F601}"), pattern);
  }
  for (const pattern of ["\\uD83D", "\\uDE00", "\\uDE00\\uD83D", "\\uD83DA", "\\uD83D\\uD83D", "\\x{D800}", "[\\uD800]", "[a-\\x{DFFF}]"]) {
    assert.equal(refusedOf(pattern).why, "aCharacterNoStringHolds", pattern);
  }
  // A high escape with a malformed escape after it spells the high surrogate, and is refused for
  // that rather than for the escape after it.
  const refused = refusedOf("\\uD83D\\u00G0");
  assert.equal(refused.why, "aCharacterNoStringHolds");
  assert.equal(refused.from, 0);
});

// Half of a surrogate pair with no other half beside it is no character, wherever the reader takes
// a character from the text, as bytes that are not UTF-8 are in Go.
it("half of a surrogate pair is refused wherever a character is taken", () => {
  const places: [string, number][] = [
    ["%s", 0], ["ab%scd", 2], ["(?:a|%s)", 5], ["%s+", 0], ["[%s]", 1], ["[^%s]", 2],
    ["[a%sb]", 2], ["[%s-z]", 1], ["[a-%s]", 1], ["\\%s", 0], ["[\\%s]", 1],
  ];
  for (const [place, at] of places) {
    for (const half of ["\uD800", "\uDFFF", "\uDE00\uD83D"]) {
      const pattern = place.replace("%s", half);
      const refused = refusedOf(pattern);
      assert.equal(refused.why, "aCharacterNoStringHolds", JSON.stringify(pattern));
      assert.equal(refused.from, at, JSON.stringify(pattern));
    }
  }
});

// Where a refusal points and what it quotes are in UTF-16 code units of the text, and quote
// characters whole.
it("a refusal quotes the construct in code units", () => {
  const cases: Record<string, PatternRefused> = {
    "é(?\u{1F600}": { why: "aGroupTheGrammarDoesNotHave", from: 1, construct: "(?\u{1F600}" },
    "éé\\p{L}": { why: "aCharacterProperty", from: 2, construct: "\\p" },
    "é{3,1}": { why: "aCountThisCannotRead", from: 1, construct: "{3,1}" },
    "é)": { why: "somethingUnclosed", from: 1, construct: ")" },
    "(é": { why: "somethingUnclosed", from: 2, construct: "" },
    "a(a|)^b": { why: "anAnchorThisCannotPlace", from: 0, construct: "a(a|)^b" },
    "[\u{1F600}\\x{D800}]": { why: "aCharacterNoStringHolds", from: 3, construct: "\\x{D800}" },
  };
  for (const [pattern, want] of Object.entries(cases)) {
    assert.deepEqual(refusedOf(pattern), want, pattern);
  }
});

// A limit is noted where it is met and the reading goes on, so text that is no pattern after it is
// refused, and the first limit met in the text is the answer.
it("a limit is answered only of a pattern", () => {
  assert.equal(refusedOf("a{134217728}(").why, "somethingUnclosed");
  const read = readPattern(`é{134217728}${"(".repeat(201)}${")".repeat(201)}`);
  assert.deepEqual(read, { beyond: { limit: "repetitionCount", from: 2, construct: "134217728" } });
  assert.ok("beyond" in readPattern("a{134217727}"), "a{134217727} is within the count and past the states");
  assert.ok("pattern" in readPattern(`${"(".repeat(200)}a${")".repeat(200)}`));
  assert.deepEqual(readPattern(`${"(".repeat(201)}a${")".repeat(201)}`),
    { beyond: { limit: "nestingDepth", from: 200, construct: "(" } });
  assert.equal(PATTERN_LIMITS.repetitionCount, 134_217_727);
  assert.equal(PATTERN_LIMITS.nestingDepth, 200);
  assert.equal(PATTERN_LIMITS.machineStates, 250_000);
});

// Groups are read with a stack of their own, and so are the anchors placed, so text nested far past
// any limit is read to its end.
it("text nested as deeply as it is long is read to its end", () => {
  const deep = 1 << 20;
  const read = readPattern(`${"(".repeat(deep)}^a$${")".repeat(deep)}`);
  assert.ok("beyond" in read && read.beyond.limit === "nestingDepth" && read.beyond.from === 200, described(read));
  assert.equal(refusedOf(`${"(".repeat(deep)}a|)^b${")".repeat(deep - 1)}`).why, "anAnchorThisCannotPlace");
  assert.equal(refusedOf("(".repeat(deep)).why, "somethingUnclosed");
});

// A sequence between two anchors is as long as the text, and is put together a part at a time, so
// text far past the limit on states is read to its end and found past it.
it("a sequence as long as the text between anchors is read to its end", () => {
  const read = readPattern(`^${"a?".repeat(1 << 20)}$`);
  assert.ok("beyond" in read && read.beyond.limit === "machineStates", described(read));
  const wide = readPattern(`[${"a".repeat(1 << 20)}]`);
  assert.ok("pattern" in wide && wide.pattern.matches("a"), described(wide));
});

// What only a JavaScript string can hold: half of a surrogate pair, which is no text.
it("a subject holding half of a surrogate pair is accepted by nothing", () => {
  for (const pattern of [".*", "[^a]*", "(?:.|\\n)*", "\\W"]) {
    const read = patternOf(pattern);
    for (const subject of ["\uD800", "a\uDC00", "\uDE00\uD83D", "\u{1F600}\uD83D"]) {
      assert.ok(!read.matches(subject), `${pattern} accepts ${JSON.stringify(subject)}`);
    }
  }
  assert.ok(patternOf(".").matches("\uFFFD"), "U+FFFD written as itself is a character");
  assert.ok(patternOf(".").matches("\u{1F600}"), "a pair is one character");
});

it("a pattern and a subject that are not strings are mistakes of the caller's", () => {
  assert.throws(() => readPattern(42 as never), TypeError);
  assert.throws(() => patternOf("a").matches(undefined as never), TypeError);
});

// What a walk keeps is the sets it worked out, and which are kept changes no answer: with no room,
// a little and the whole room, every subject of every pattern is answered alike, and alike again
// the second time, from what the first time kept.
it("what a walk keeps changes no answer", () => {
  const pieces = ["a", "b", "é", "\u{1F600}", ".", "[ab]", "[^a]", "\\w", "(?:a|b)", "(?:ab|a)", "a*", "b+",
    "(?:a|é)?", "[a-é]{1,3}", "(?:\u{1F600}|.)*", "^", "$"];
  const letters = ["a", "b", "é", "\u{1F600}", "c", "\n"];
  let rng = 1;
  const next = (n: number) => {
    rng = (Math.imul(rng, 1664525) + 1013904223) >>> 0;
    return (rng >>> 8) % n;
  };
  for (let i = 0; i < 2000; i++) {
    let pattern = "";
    for (let k = next(6); k >= 0; k--) {
      pattern += pieces[next(pieces.length)];
    }
    const subjects: string[] = [];
    for (let k = 0; k < 8; k++) {
      let subject = "";
      for (let m = next(12); m > 0; m--) {
        subject += letters[next(letters.length)];
      }
      subjects.push(subject);
    }
    const read = readText(pattern);
    if (!("meaning" in read)) {
      continue;
    }
    const machine = build(read.meaning);
    const answers = [0, 1500, 2 << 20].map((room) => {
      const walk = new Walk(machine, room);
      return [...subjects, ...subjects].map((subject) => walk.matches(subject));
    });
    for (const these of answers.slice(1)) {
      assert.deepEqual(these, answers[0], `${JSON.stringify(pattern)} for ${JSON.stringify(subjects)}`);
    }
  }
});

// A match takes time linear in the subject, whatever the pattern, and a pattern of as many states
// as the limit is built and matched.
it("a match is linear in the subject and a machine at the limit is matched", () => {
  const nested = patternOf("(a*)*b");
  const many = "a".repeat(100_000);
  let started = performance.now();
  assert.ok(!nested.matches(many));
  assert.ok(nested.matches(`${many}b`));
  const nestedTook = performance.now() - started;
  const wide = readPattern(`a{0,${250_000 - 3}}`);
  assert.ok("pattern" in wide, described(wide));
  started = performance.now();
  assert.ok(wide.pattern.matches("a".repeat(1000)));
  assert.ok(!wide.pattern.matches("b"));
  const wideTook = performance.now() - started;
  assert.ok(nestedTook < 2000, `(a*)*b took ${nestedTook} ms`);
  assert.ok(wideTook < 10_000, `a pattern at the limit took ${wideTook} ms`);
});
