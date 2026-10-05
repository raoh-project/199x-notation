# Working on notation-199x

What belongs in this repository, and the contract of each entry point, are in the
[README](README.md). This is how the work on it is done.

## Generating the tables

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

## Testing

The vectors in `suite/` are what every implementation is held to, in a format each language reads
with its standard library; `suite/README.md` states it. The fixtures of a format in `image/` are
written the same way, and what reads that format is held to them.

The Java tests read `ucd/`, `suite/` and `image/`, so they run in `java/`:

```sh
cd java && mvn test
```

NullAway checks the main Java sources against `@NullMarked` on every compile. Nothing else of Error
Prone runs.

The Go tests read `ucd/` and `suite/` too, and run in `go/`:

```sh
cd go && go test ./...
```

The Rust tests read `ucd/`, `suite/` and `image/`, and run in `rust/`:

```sh
cd rust && cargo test
```

The crate is built, linted and tested with the Rust that `rust/rust-toolchain.toml` names, and CI
also builds it with the oldest Rust its `rust-version` says it builds with.

The PHP tests read `ucd/` and `suite/`, and run in `php/` with the tools Composer installs:

```sh
cd php && composer install && vendor/bin/phpunit
```

CI tests the PHP package on the oldest PHP it takes and the newest, without intl, and runs PHPStan at
its highest level over it. PHPUnit needs mbstring, so a test holds the sources to asking neither
mbstring nor iconv.

The Go, Rust and PHP tests that read `ucd/`, `suite/` or `image/` are skipped where the files are not
there, as in a copy a package manager fetched. CI sets `NOTATION199X_REQUIRE_SUITE`, which makes a
missing file a failure instead.

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

## Branches and releases

Work goes onto `develop`, and `main` carries releases. [docs/releasing.md](docs/releasing.md) is
the whole of how one is cut.
