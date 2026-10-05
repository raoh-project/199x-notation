# @raoh/notation-199x

The TypeScript implementation of the rules for reading text that
[Raoh](https://github.com/raoh-project) and [Souther](https://github.com/souther-lang/souther)
share: Unicode 18.0.0 default case conversion and normalization, the `White_Space` set, order and
length in Unicode scalar values, the lexical grammar of temporal text, and the pattern language.

This package is developed in `ts/` of
[raoh-project/notation-199x](https://github.com/raoh-project/notation-199x), beside the Java, Go,
Rust and PHP implementations, and is held to the same test vectors. Issues and pull requests go
there.

It runs on Node from 22 on, and in any engine with ES2024, and depends on nothing. No rule asks
the engine's Unicode support or its `Date`: `toLowerCase`, `normalize`, `RegExp`, `\s`,
`String.prototype.trim` and `Intl` answer by the engine's release, and `Date` reads text by rules of
its own. The tables each rule reads are a module of their own, so a bundler leaves out those of the
rules a program does not call.

## What it has

Every rule is stated of text that is a sequence of Unicode scalar values. A JavaScript string can
hold half of a surrogate pair, so `illFormedAt` is the question a caller asks before it takes text
in. The other functions take well-formed text and do not ask it, apart from the temporal grammar,
which reads any string and finds no form in a code unit past ASCII, `readPattern`, which refuses half
a pair, and a pattern's `matches`, which accepts no text that holds one.

```ts
import {
  checkTemporal, compare, illFormedAt, isWhiteSpace, lowercase, normalizeWithin, readInstant,
  readPattern, scalarCount, uppercaseWithin,
} from "@raoh/notation-199x";

illFormedAt("a\uD83D");                            // 1
scalarCount("a\u{1F600}");                         // 2
compare("\uFFFF", "\u{10000}");                    // -1, where "\uFFFF" < "\u{10000}" is false
isWhiteSpace(0x85);                                // true
lowercase("ΟΔΥΣΣΕΥΣ");                             // "οδυσσευς"
uppercaseWithin("straße", 5);                      // undefined: "STRASSE" is 7 long
normalizeWithin("NFC", "e\u0301", 1);              // "é"
checkTemporal("instant", "2016-12-31T23:59:60Z");  // "leapSecond"
readInstant("2026-09-30T24:00:00+09:00");          // { value: { epochSecond: 1790780400n, nanosecond: 0 } }

const read = readPattern("[A-Z]{3}-[0-9]+");
if ("pattern" in read) {
  read.pattern.matches("ABC-123");                 // true
}
```

A bounded conversion or normalization takes the most scalar values its answer may hold, and
answers `undefined` where the answer is longer, which it finds out before it writes more than that.
An instant's epoch second is a `bigint`, since the moments an instant holds reach past the integers a
`number` holds exactly. A pattern keeps what its matches work out, where a character leads from the
states a match is in, for the next match, within about two megabytes; that changes how fast a match
is and never what it answers.

Working on the package itself takes Node 22.18.0 or later, which runs the tests as the TypeScript
they are written in; `package.json` says the first in `engines` and the second in `devEngines`.
