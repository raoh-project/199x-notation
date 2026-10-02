# suite

The vectors every implementation runs. An implementation agrees with another because both answer
every line here, and not because their code happens to. Each language has a test that reads these
files with its standard library and holds the implementation to every line.

Normalization is not here: it is held to `ucd/18.0.0/NormalizationTest.txt`, which Unicode publishes
for that and which is read the same way.

## What a line holds

Every field a line asserts is a value a named source says an implementation answers: the Raoh
specification or the Souther specification for a rule about text, and this repository's README for
how an implementation is called, such as the bounded forms. A distinction a source draws without
giving the two sides different answers is not such a value: Souther refuses a leap second for what
it means and other text for its form, and reports both under one code, so `temporal.txt` holds that
a text is refused and not which of the two it is. A file names its sources at the top, and a field
whose value those sources do not decide is left empty and is not asserted. So a line asserts as much
as its sources say and no more: `a{134217728}` is past two limits and the sources do not say which
one is the answer, so its line asserts that it is past a limit and leaves the limit empty. What an
implementation answers beyond that, such as the reason text is refused, where a refusal points or
what it quotes, is tested in that implementation's own language.

A field is added to a file once a source decides it, and not before.

## Files

| File | A line | What is held |
| --- | --- | --- |
| `case.txt` | text ; direction ; bound ; outcome ; mapped | Default case conversion, with `Final_Sigma` and the bounded forms |
| `white-space.txt` | code point | The `White_Space` set, every member of it |
| `scalar-length.txt` | text ; length | Length in scalar values |
| `scalar-order.txt` | a ; b ; order | Order by scalar values |
| `temporal.txt` | kind ; text ; outcome | Dates, times, date-times, date-times with an offset, and instants: what is admitted and what is refused |
| `pattern-read.txt` | pattern ; outcome ; limit | What is read as a pattern, what is refused, and what is past a limit |
| `pattern-match.txt` | pattern ; subject ; accepted | What a pattern accepts |
| `pattern-states.txt` | pattern ; states | The states a pattern is counted as, against the limit |

The comment at the top of each file says what its fields hold. `white-space.txt` lists the whole
set, so a runner asks every scalar value and holds the ones no line names to not being white space.
`pattern-states.txt` holds a count where it decides something, and its comment gives the rule a
runner builds the patterns at and one past the limit by.

## Format

Every file is UTF-8, and a line of vectors is ASCII.

- A line that is empty is skipped, and so is a line whose first character is `#`. There are no
  comments after a vector.
- Before the first line of vectors a file has a line `# Source:`, and under it one line for each
  source, `#   <document> — <section>`. A runner refuses a file without one.
- The fields of a line are separated by `;`, and spaces on either side of a field are not part of
  it. Every line of a file has the same number of fields, and a field that is not asserted is empty.
- A text is its Unicode scalar values, each four to six upper case hex digits, separated by one or
  more spaces. The empty text is an empty field. A surrogate is no scalar value, so no text here holds
  one.
- A number is unsigned decimal, and each file says what it counts: the length of a text and a bound
  on a conversion count scalar values, and a pattern's states count states.
- Anything else, a direction, an outcome or a limit, is an upper case identifier the file's comment
  lists, and a yes or no is `true` or `false`.

A runner reads every field of every line, and reads it as the format writes it: a field it does
not assert must be empty, an identifier must be one the file lists, a number has no sign. A line
that is not written so is an error in the file, and is reported as one rather than passed over, so
nothing written in a file goes unchecked.

Writing a text as scalar values keeps it apart from how any language holds one, UTF-8, UTF-16 or
bytes, and lets a line write a tab, a line break, U+0000 or a `;` as readily as a letter. What
only one language's strings can hold, such as half of a surrogate pair in a Java `String`, is not
a vector here, and is tested in that language.
