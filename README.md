# 199x-notation

The rules for reading text that [Raoh](https://github.com/raoh-project) and
[Souther](https://github.com/souther-lang/souther) share, implemented once per language.

Both projects define what a string means independently of the platform they run on: which Unicode
version a case mapping uses, which texts are a date, which strings a pattern accepts. A host
language's standard library answers these questions differently from release to release, and
differently from another host language. This repository holds the implementations that answer them
the same way everywhere.

## Status

The Java implementation is here, moved from Souther's runtime and compiler, and the Go, Rust, PHP
and TypeScript implementations beside it. Souther, raoh-java, raoh-go, raoh-rust, raoh-php and
raoh-ts do not depend on them yet, and all five are held to `suite/`.

## What belongs here

- Unicode 18.0.0 default case conversion, with no locale or language tailoring, including the
  `Final_Sigma` condition.
- Unicode 18.0.0 normalization: NFC, NFD, NFKC and NFKD.
- The `White_Space` set, as of Unicode 18.0.0.
- Order and length of text counted in Unicode scalar values.
- The lexical grammar of dates, times, date-times, date-times with an offset, and instants.
- The pattern language: reading a pattern, refusing what is not one, and matching in time linear in
  the input.

The normative definitions are in the
[Raoh Specification](https://github.com/raoh-project/raoh-specification) and the
[Souther specification](https://github.com/souther-lang/souther/blob/develop/specification.adoc).
This repository implements them and defines no rule about text of its own: which text is a date, a
pattern or white space, and what a conversion answers, is theirs to say.

What it does define is how its implementations are called: entry points, each an operation with a
contract of its own. For those contracts, and only for those, this README is the source. An entry
point is what it does and what it answers, not how one language spells it: Java takes a checkpoint
as an interface, where Go would take a `context.Context`. An entry point an implementation has
beyond the ones described here, such as Java's `Normalization.combiningClass`, is that
implementation's own.

Every implementation has an entry point for each rule above, and a bounded case conversion and a
bounded normalization besides. A bounded case conversion or normalization takes the most scalar
values its answer may hold, answers what the unbounded one does where that answer is no longer than
the bound, and answers nothing where it is longer. The bound is on the work as well as on the
answer: what one holds and how much of the text it reads turn on the bound and not on the length of
the text, so it reads no further once what it has read shows the answer to be longer than the bound.
Each of the other entry points described below is in an implementation whose callers need that
one, apart from the others: the operations on the machine a pattern means, writing a machine as an
image, running a match from an image, and running a match, a bounded normalization or a bounded case
conversion with a checkpoint. Where an implementation has one, it holds to the contract every other
implementation that has it holds to.

## What does not belong here

Anything that knows about decoders, issues, paths, or a Souther value. An implementation here takes
text and answers a question about it. It depends on neither Raoh nor Souther, so that Souther's
runtime can use it without depending on a decoder library.

A pattern is held to three limits, the same numbers in every implementation and each decided from
the text: a repetition count of at most 134,217,727, groups nested at most 200 deep, and at most
250,000 states once its repetitions are written out, counted from what is written without building
anything. They are the specifications' limits on an admissible pattern, and a pattern past one is
told apart from text that is no pattern. Every pattern within them has a machine, which is what a
match is built from, so no limit of how a machine is built or carried decides which patterns a
caller takes. A caller that runs a pattern where it reads it takes the matcher. One that carries the
machine elsewhere, as a compiler writing it into a class does, writes it out as an image, and what
runs the class runs a match from the image: writing an image and running a match from one are two
entry points, and an implementation may have either without the other. Only the image has a size of
its own: a writer writes no image of more than 8,388,608 characters, and answers that there is none
where the machine it writes would take more. What an image accepts is what the pattern does, and
which of the machines that accept it is written is the writer's: Java writes the smallest
deterministic machine as P2 where it makes one and its image fits, and the machine the pattern's
shape makes as P1 otherwise. It writes no deterministic machine as P1, since a reader's check of
one is not bounded by the image's length. So whether a pattern has an image can differ between implementations, which says
nothing about what the pattern means. An image begins with the format it is written in. The formats
are this repository's, one set whichever implementation writes or reads an image, and an
implementation that runs a match from an image reads every format this repository has defined up to
that implementation's release: an image in any of them runs on it, whichever implementation wrote
it, so a class Souther compiled against one release runs against a later one. An image one
implementation writes is read by every implementation that reads its format, and accepts the same
strings there. The formats are defined apart from any implementation's code: P1 in
[`image/P1.md`](image/P1.md), and P2, which writes only deterministic machines, in
[`image/P2.md`](image/P2.md).

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

Java has the three entry points with a checkpoint, for Souther, which evaluates example rows under a
step limit and a deadline and has to stop a rule inside one of those calls. An implementation gains
one when a caller of its own has to stop that rule part of the way through, beside the entry point
without one, so nothing that calls that changes.

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
| `image/` | The image formats, each with the fixtures every implementation that reads it runs |
| `java/` | Maven artifact `net.unit8.199x:199x-notation`, package `net.unit8.notation199x` |
| `rust/` | Crate `notation199x` |
| `go/` | Module `github.com/raoh-project/199x-notation/go`, package `notation199x` |
| `php/` | Composer package `raoh/199x-notation`, namespace `Raoh\Notation199x` |
| `ts/` | npm package `@raoh/199x-notation` |

An identifier cannot begin with a digit in any of these languages, so code spells the name
`notation199x`. A Composer package name can, so the PHP package is `raoh/199x-notation`, under the
vendor raoh-php is published under. An npm package name can too, and `@raoh/199x-notation` is under the scope raoh-ts
is published under.

Tables are generated from `ucd/` and checked in. Generation is run by hand and never during a
build: taking a later Unicode version is a change to the specifications, not a dependency update.

```sh
java gen/Generate.java ucd/18.0.0
```

The generator reads the database once into one model, where every fact about Unicode is derived
and checked against the properties Unicode publishes, and writes each language's tables from that
model with an emitter of its own. An emitter decides which of the model's facts its implementation
holds as generated and how its language holds them, and derives no fact about Unicode of its own, so
every implementation answers from the same facts. What a case conversion or a normalization asks of
each code point it reads, its mapping, its combining class, its decomposition, whether it is a
stable starter, whether it is Cased or Case_Ignorable, and what it composes into with the code point
before it, is read from a generated table the code point, or the pair, indexes in a fixed number of
steps however many entries the table has, rather than worked out from other tables or searched for.
PHP holds some of them as arrays keyed by the character, which it looks up as directly. A search is
left only where such a table has said a code point has an entry, as the Final_Sigma mapping of the
one code point that has one. No table is written by hand or read from a resource at run time.

The vectors in `suite/` are what every implementation is held to, in a format each language reads
with its standard library; `suite/README.md` states it. The fixtures of a format in `image/` are
written the same way, and what reads that format is held to them.

The Java tests read `ucd/`, `suite/` and `image/`, so they run in `java/`:

```sh
cd java && mvn test
```

Both Java packages are `@NullMarked`, and NullAway checks the main sources against that on every
compile. Nothing else of Error Prone runs.

The Go tests read `ucd/` and `suite/` too, and run in `go/`:

```sh
cd go && go test ./...
```

A Go module made from a subdirectory holds only what is under it, so a copy of the module that `go`
fetched has neither `ucd/` nor `suite/`. Where they are not there the tests that read them are
skipped, so that a caller's `go test all` does not fail on files this module was never published
with. CI sets `NOTATION199X_REQUIRE_SUITE`, which makes a missing file a failure instead.

The Rust tests read `ucd/`, `suite/` and `image/`, and run in `rust/`:

```sh
cd rust && cargo test
```

A crate packaged from `rust/` holds only what is under it, so as for Go, the tests that read files
outside it are skipped where the files are not there, and `NOTATION199X_REQUIRE_SUITE` makes that a
failure. The crate is `no_std` and allocates through `alloc`; a run time without the standard
library provides the global allocator. It is built, linted and tested with the Rust that
`rust/rust-toolchain.toml` names, and CI also builds it with the oldest Rust its `rust-version` says
it builds with.

The PHP tests read `ucd/` and `suite/`, and run in `php/` with the tools Composer installs:

```sh
cd php && composer install && vendor/bin/phpunit
```

A package made from `php/` holds only what is under it, so as for Go and Rust, the tests that read
files outside it are skipped where the files are not there, and `NOTATION199X_REQUIRE_SUITE` makes
that a failure. The package needs a 64-bit PHP 8 from 8.2 on, as its `composer.json` requires, and
no extension, and CI tests it on the oldest PHP it takes and the newest, without intl, and runs
PHPStan at its highest level over it. PHPUnit needs mbstring, so a test holds the sources to asking
neither mbstring nor iconv. A PHP string is bytes, so the question a caller asks before it takes
text in is whether the string is UTF-8, and a pattern reader refuses one that is not.

The limits on a pattern are not lowered by how PHP holds one. Nothing whose number grows with a
pattern is a PHP array or object of its own: a pattern's tree, its sets of symbols and its machine
are flat lists of numbers, and the sets of states a match keeps to go faster are held to a budget
of their own and forgotten past it. The two sets every match needs, the one it starts in and the
one it is in when the others are forgotten for it, are kept whatever they take and beside the
budget, not in it, so what is kept past the budget is at most twice the machine's states. So a
pattern at the limit of 250,000 states is read and matched within PHP's default memory limit of
128 MB, whether it repeats one character or writes 250,000 different ones, and the PHP tests hold
patterns of both kinds to that in a process of their own. What a pattern takes besides its states
grows with the ranges its classes name, about 32 bytes each, which the limits do not bound: a
pattern whose classes name millions of ranges needs more room than that.

Rust reads images of P1 and P2 and writes none. Java's tests write the image of each pattern in
`suite/pattern-match.txt` to `java/target/images/pattern-match.txt`, which is never checked in, and
the Rust tests read each of those images and hold it to what the suite says the pattern accepts,
where `NOTATION199X_JAVA_IMAGES` names the file:

```sh
cd java && mvn test
cd ../rust && NOTATION199X_JAVA_IMAGES=../java/target/images/pattern-match.txt cargo test --test java_images
```

CI checks the files in `ucd/` against their checksums, runs the generator and fails if the
checked-in tables differ, runs the tests of each implementation, and has Rust read the images Java
wrote in that run.

## Releasing

Each implementation is released on its own, and its version is its own: it says what changed in
that implementation's API, and a Java release and a Go release with the same number have nothing
to do with each other. What the implementations agree on is held by the commit, the pinned Unicode
version and `suite/`, not by a version number.

### Java

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

### Go

The module is `github.com/raoh-project/199x-notation/go`, and a release of it is a tag: nothing in
`go/` names its version. A tag on a module in a subdirectory begins with the subdirectory, so the
module's version `vX.Y.Z` is the tag `go/vX.Y.Z`. The first release is `go/v0.1.0`.

1. Merge what is to be released into `main`, as for a Java release.
2. Tag the commit on `main` `go/vX.Y.Z` and push the tag. CI runs the Go tests on it.
3. Ask the module proxy for the version, so that it holds what the tag holds from then on:
   ```sh
   GOPROXY=https://proxy.golang.org go list -m github.com/raoh-project/199x-notation/go@vX.Y.Z
   ```

A tag that has been pushed is never moved: the proxy and the checksum database keep the first
contents a version had, and a module fetched by version is checked against them.

### PHP

The package is `raoh/199x-notation`, and a release of it is a tag: nothing in `php/` names its
version. Packagist reads `composer.json` at the root of a repository, and this one's is in `php/`,
so a release is published to a mirror whose root is `php/`,
[raoh-project/199x-notation-php](https://github.com/raoh-project/199x-notation-php), and Packagist
reads the mirror. The mirror is written by CI and by nothing else: nobody commits to it, and an
issue or a pull request there is sent here. As for Go, a tag here begins with the directory, so the
package's version `vX.Y.Z` is the tag `php/vX.Y.Z` here and the tag `vX.Y.Z` on the mirror. The
first release is `php/v0.1.0`.

1. Merge what is to be released into `main`, as for a Java release.
2. Tag the commit on `main` `php/vX.Y.Z` and push the tag. The `PHP release` workflow fails a tag
   that is not `php/` and a version, or that names a commit not on `main`. It runs the whole of CI
   on the commit, and only once that passes writes the files git tracks under `php/` and the
   license as one commit on the mirror's `main`, and tags it `vX.Y.Z` with a message naming the
   commit here it was written from.
3. Packagist takes the version from the mirror's tag, by the mirror's webhook.

A tag that has been pushed is never moved, here or on the mirror: Packagist keeps the commit a
version was first published at, and the workflow refuses a version the mirror already has. A
release that is wrong is followed by another.

The workflow pushes to the mirror with a deploy key that can write to it, held here as the secret
`PHP_MIRROR_DEPLOY_KEY`. Setting up the mirror is done once: create
`raoh-project/199x-notation-php` empty, add the public half of the key to it as a deploy key with
write access and the private half here as that secret, push the first tag, and submit the mirror
to Packagist under the `raoh` vendor, with its GitHub hook on.

### TypeScript

The package is `@raoh/199x-notation`, published from `ts/` by the `TypeScript publish` workflow and by
nothing else. `ts/package.json` on `develop` holds the next version as `X.Y.Z-dev`. A push to
`develop` that changes `ts/` publishes the commit it brings, once CI has passed on it, as that
version followed by the time of the commit and the commit, `X.Y.Z-dev.YYYYMMDDHHMMSS.gHHHHHHHHHHHH`,
under the dist-tag `dev`. npm takes a version once and never again, so a development version is a
commit, as a timestamped Maven snapshot is: the commit makes two commits two versions, which the time
alone does not, and the time puts them in the order they were made.

`dev` names the newest state of the package on `develop`, and not whichever run happened to publish
last. Just before it publishes, a run asks `develop` as it is then whether a later commit that
changes `ts/` has reached it, and where one has, it publishes nothing and leaves `dev` to that
commit's run; one run publishes at a time, so the run that finds itself newest publishes before any
later commit's run asks, whatever order GitHub starts the runs in. So the commits of one push but
the last, and a push a later one overtakes before its run publishes, have no version of their own;
and where the later commit's run fails, `dev` stays where it was until a push whose run passes.

`npm install @raoh/199x-notation` takes `latest`, which is a release, and a range written for
releases, `^0.2.0`, takes no development version: npm takes a prerelease only for a range that names
one of the same `X.Y.Z`, and `^0.2.0-dev.20261004000000.g0` takes every later one. So a project that
means one commit writes its version exactly, `npm install --save-exact @raoh/199x-notation@<version>`,
as `npm install` otherwise saves a range.

`ts/scripts/publish.sh` says what version a ref makes, under which dist-tag, and publishes it, and CI
takes each path it can take as a dry run on every pull request (`ts/scripts/try-publish.sh`), so
each is taken before a push takes it. A release is a tag, as for Go and PHP, and the tag begins with
the directory:

1. On a branch from `develop`, set `ts/package.json`'s version to `X.Y.Z` and open a pull request to
   `main`, as for a Java release.
2. Merge it, and tag the merge commit on `main` `ts/vX.Y.Z`. The workflow fails a tag that is not
   `ts/` and a version, that is not the version `ts/package.json` holds, or that names a commit not
   on `main`. It runs the whole of CI on the commit, and only once that passes publishes `X.Y.Z`:
   under `latest` where it is newer than what `latest` names as it is published, and under
   `release-X.Y` where it is not, so a release whose run comes after a newer one's never takes
   `latest` from it. What `latest` names is asked of the registry, which holds what was published,
   so a tag the workflow refused, or whose run failed, has no say in it.
3. Merge `main` back into `develop`, and set `ts/package.json` there to `<next version>-dev`.

The workflow logs in with nothing: npm proves to the registry that it runs in this workflow of this
repository, which the package's settings on npmjs.com name as its trusted publisher, and the
registry records with each version the commit and the run it was built in. A trusted publisher is
named for a package that exists, so the package's first version is published by hand from a
checkout, and the trusted publisher named after it: repository `raoh-project/199x-notation`, workflow
`ts-publish.yml`, with `npm publish` allowed. A trusted publisher named since September 3, 2026
allows `npm stage publish` and allows `npm publish` only where it is chosen, and the workflow
publishes with `npm publish`. It needs nothing else: it reads what `latest` names from the registry
without logging in, as anyone can of a public package.

## The name

199X is the year *Fist of the North Star* is set in. Raoh and Souther take their names from it.

## License

[Apache License 2.0](LICENSE)
