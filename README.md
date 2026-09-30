# 199x-notation

The rules for reading text that [Raoh](https://github.com/raoh-project) and
[Souther](https://github.com/souther-lang/souther) share, implemented once per language.

Both projects define what a string means independently of the platform they run on: which Unicode
version a case mapping uses, which texts are a date, which strings a pattern accepts. A host
language's standard library answers these questions differently from release to release, and
differently from another host language. This repository holds the implementations that answer them
the same way everywhere.

## Status

The Java implementation is here, moved from Souther's runtime and compiler. Souther and raoh-java
do not depend on it yet. The Rust, Go and PHP implementations and `suite/` are not here yet. See the
issues.

## What belongs here

- Unicode 18.0.0 default case conversion, with no locale or language tailoring, including the
  `Final_Sigma` condition.
- Unicode 18.0.0 normalization: NFC, NFD, NFKC and NFKD.
- The `White_Space` set, as of Unicode 18.0.0.
- Order and length of text counted in Unicode scalar values.
- The lexical grammar of dates, times and instants.
- The pattern language: reading a pattern, refusing what is not one, and matching in time linear in
  the input.

The normative definitions are in the
[Raoh Specification](https://github.com/raoh-project/raoh-specification) and the
[Souther specification](https://github.com/souther-lang/souther/blob/develop/specification.adoc).
This repository implements them and defines nothing of its own.

## What does not belong here

Anything that knows about decoders, issues, paths, or a Souther value. An implementation here takes
text and answers a question about it. It depends on neither Raoh nor Souther, so that Souther's
runtime can use it without depending on a decoder library.

## Layout

One directory per language, beside the data they are all generated from and checked against:

| Directory | Contents |
| --- | --- |
| `ucd/` | The Unicode Character Database files of the pinned version, with their checksums |
| `gen/` | The programs that generate the tables from `ucd/` |
| `suite/` | Test vectors every implementation runs |
| `java/` | Maven artifact `net.unit8.notation199x:199x-notation`, package `net.unit8.notation199x` |
| `rust/` | Crate `notation199x` |
| `go/` | Package `notation199x` |
| `php/` | Package `notation199x` |

Tables are generated from `ucd/` and checked in. Generation is run by hand and never during a
build: taking a later Unicode version is a change to the specifications, not a dependency update.

```sh
java gen/GenerateCaseTables.java ucd/18.0.0
java gen/GenerateNormalizationTables.java ucd/18.0.0
```

The Java tests read `ucd/`, so they run in `java/`:

```sh
cd java && mvn test
```

An identifier cannot begin with a digit in any of these languages, so code spells the name
`notation199x`.

## The name

199X is the year *Fist of the North Star* is set in. Raoh and Souther take their names from it.

## License

[Apache License 2.0](LICENSE)
