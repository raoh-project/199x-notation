# @raoh/199x-notation

The TypeScript implementation of the rules for reading text that
[Raoh](https://github.com/raoh-project) and [Souther](https://github.com/souther-lang/souther)
share. So far it has the Unicode 18.0.0 `White_Space` set, order and length in Unicode scalar
values, and the lexical grammar of temporal text. Case conversion, normalization and the pattern
language come next.

This package is developed in `ts/` of
[raoh-project/199x-notation](https://github.com/raoh-project/199x-notation), beside the Java, Go,
Rust and PHP implementations, and is held to the same test vectors for the rules it has. Issues and
pull requests go there.

It runs on Node from 22 on, and in any engine with ES2024, and depends on nothing. No rule asks
the engine's Unicode support or its `Date`: `\s`, `String.prototype.trim` and `Intl` answer by the
engine's release, and `Date` reads text by rules of its own.

## What it has

Every rule is stated of text that is a sequence of Unicode scalar values. A JavaScript string can
hold half of a surrogate pair, so `illFormedAt` is the question a caller asks before it takes text
in. The other functions take well-formed text and do not ask it, apart from the temporal grammar,
which reads any string and finds no form in a code unit past ASCII.

```ts
import { checkTemporal, compare, illFormedAt, isWhiteSpace, readInstant, scalarCount }
  from "@raoh/199x-notation";

illFormedAt("a\uD83D");                       // 1
scalarCount("a\u{1F600}");                    // 2
compare("￿", "\u{10000}");               // -1, where "￿" < "\u{10000}" is false
isWhiteSpace(0x85);                           // true
checkTemporal("instant", "2016-12-31T23:59:60Z");  // "leapSecond"
readInstant("2026-09-30T24:00:00+09:00");     // { value: { epochSecond: 1790780400n, nanosecond: 0 } }
```

An instant's epoch second is a `bigint`, since the moments an instant holds reach past the integers a
`number` holds exactly.

Working on the package itself takes Node 22.18.0 or later, which runs the tests as the TypeScript
they are written in; `package.json` says the first in `engines` and the second in `devEngines`.
