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
- The lexical grammar of dates, times, date-times, date-times with an offset, and instants.
- The pattern language: reading a pattern, refusing what is not one, and matching in time linear in
  the input. With it, the machine a pattern means and the operations on such machines, which is
  what a match is built from.

The normative definitions are in the
[Raoh Specification](https://github.com/raoh-project/raoh-specification) and the
[Souther specification](https://github.com/souther-lang/souther/blob/develop/specification.adoc).
This repository implements them and defines nothing of its own.

## What does not belong here

Anything that knows about decoders, issues, paths, or a Souther value. An implementation here takes
text and answers a question about it. It depends on neither Raoh nor Souther, so that Souther's
runtime can use it without depending on a decoder library.

A pattern is held to three limits, the same numbers in every implementation and each decided from
the text: a repetition count of at most 134,217,727, groups nested at most 200 deep, and at most
250,000 states once its repetitions are written out, counted from what is written without building
anything. They are the specifications' limits on an admissible pattern, and a pattern past one is
told apart from text that is no pattern. Every pattern within them has a machine, so no limit of
how a machine is built or carried decides which patterns a caller takes. A caller that runs a
pattern where it reads it takes the matcher; one that carries the machine elsewhere, as a compiler
writing it into a class does, takes it written out as an image, and only the image has a size of
its own. An image begins with the format it is written in, and a release reads every format an
earlier release wrote, so a class compiled against one release runs against a later one.

What a caller asks about the patterns it holds beyond that, and how much it is willing to spend on
an answer, is the caller's. The operations on machines take their limits as an argument. Souther's
compiler keeps its own analysis of patterns and its budgets for it.

A match, a bounded normalization and a bounded case conversion can also be run with a checkpoint
the caller hands in, for a caller that may have to stop one part of the way through. The rule asks
it whether to go on and holds no allowance of its own: what the caller counts, steps or a deadline,
stays with the caller. Each rule says how often it asks, in what it looks at. Between two asks it
goes over no more of the text, or of the machine a pattern is run as, than one of what it counts,
and it asks wherever one character can make it go over as many others as the text or the machine
has, not only once a character. It makes no room from the size of what it was handed ahead of the
work. What the platform does in one operation, such as copying the answer into a string, is not
asked inside. A rule that was stopped answers that it was stopped, apart from its own answers,
so a stopped match is neither accepted nor refused. The same rules run without a checkpoint answer
as they did.

Every rule is stated of text that is a sequence of Unicode scalar values. Where a language's string
can hold something else, as a Java `String` can hold half of a surrogate pair, the implementation
has the question a caller asks before it takes text in, and a reader of untrusted text refuses it
rather than failing.

## Layout

One directory per language, beside the data they are all generated from and checked against:

| Directory | Contents |
| --- | --- |
| `ucd/` | The Unicode Character Database files of the pinned version, with their checksums |
| `gen/` | The program that generates the tables from `ucd/` |
| `suite/` | Test vectors every implementation runs |
| `java/` | Maven artifact `net.unit8.199x:199x-notation`, package `net.unit8.notation199x` |
| `rust/` | Crate `notation199x` |
| `go/` | Package `notation199x` |
| `php/` | Package `notation199x` |

Tables are generated from `ucd/` and checked in. Generation is run by hand and never during a
build: taking a later Unicode version is a change to the specifications, not a dependency update.

```sh
java gen/Generate.java ucd/18.0.0
```

The Java tests read `ucd/`, so they run in `java/`:

```sh
cd java && mvn test
```

Both packages are `@NullMarked`, and NullAway checks the main sources against that on every
compile. Nothing else of Error Prone runs.

CI checks the files in `ucd/` against their checksums, runs the generators and fails if the
checked-in tables differ, and runs the tests.

## Releasing

`develop` holds the next version as a snapshot, `X.Y.Z-SNAPSHOT`, and a release is that version
without the suffix. A snapshot is deployed from anywhere with `cd java && mvn clean deploy`. A
release is deployed from the commit its tag names, so what Central holds is what the tag holds:

1. On a branch from `develop`, set the version to the release and open a pull request to `main`:
   ```sh
   cd java && mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```
2. Merge it, and tag the merge commit on `main` `vX.Y.Z`. CI fails a tag that is not `v` and the
   version, or whose version is a snapshot.
3. Deploy from a checkout of the tag:
   ```sh
   git checkout vX.Y.Z
   cd java && mvn -Prelease clean deploy
   ```
   The `release` profile refuses a snapshot version, attaches the sources and the Javadoc, and
   signs everything, and the Central Portal publishes the release once it has validated it.
   `clean`, because NullAway runs inside javac and a compile Maven thinks is up to date is not
   checked.
4. Merge `main` back into `develop`, so that the next release's pull request starts from this one,
   and set `develop` to the next snapshot:
   ```sh
   cd java && mvn versions:set -DnewVersion=<next version>-SNAPSHOT -DgenerateBackupPoms=false
   ```

An identifier cannot begin with a digit in any of these languages, so code spells the name
`notation199x`.

## The name

199X is the year *Fist of the North Star* is set in. Raoh and Souther take their names from it.

## License

[Apache License 2.0](LICENSE)
