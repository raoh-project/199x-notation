# suite

The vectors every implementation runs. An implementation agrees with another because both answer
every line here, and not because their code happens to. Each language has a test that reads these
files with its standard library and holds the implementation to every line.

Normalization is not here: it is held to `ucd/18.0.0/NormalizationTest.txt`, which Unicode publishes
for that and which is read the same way.

## Files

| File | A line | What is held |
| --- | --- | --- |
| `case.txt` | text ; direction ; bound ; outcome ; mapped | Default case conversion, with `Final_Sigma` and the bounded forms |
| `white-space.txt` | code point | The `White_Space` set, every member of it |
| `scalar-length.txt` | text ; length | Length in scalar values |
| `scalar-order.txt` | a ; b ; order | Order by scalar values |
| `temporal.txt` | kind ; text ; outcome | Dates, times, date-times, date-times with an offset, and instants, and their refusals |
| `pattern-read.txt` | pattern ; outcome ; why | What is read as a pattern, what is refused and why, and what is past a limit |
| `pattern-match.txt` | pattern ; subject ; accepted | What a pattern accepts |
| `pattern-states.txt` | pattern ; states | The states a pattern is counted as, against the limit |

The comment at the top of each file says what its fields hold. `white-space.txt` lists the whole
set, so a runner asks every scalar value and holds the ones no line names to not being white space.
`pattern-states.txt` holds a count where it decides something, and its comment gives the rule a
runner builds the patterns at and one past the limit by.

Only what the specifications state is here. They do not state where in a pattern a refusal points
or what it quotes, so `pattern-read.txt` holds the refusal and not its place; an implementation that
answers those is tested in its own language.

## Format

Every file is UTF-8, and a line of vectors is ASCII.

- A line that is empty is skipped, and so is a line whose first character is `#`. There are no
  comments after a vector.
- The fields of a line are separated by `;`, and spaces on either side of a field are not part of
  it. Every line of a file has the same number of fields.
- A text is its Unicode scalar values, each four to six upper case hex digits, separated by one or
  more spaces. The empty text is an empty field. A surrogate is no scalar value, so no text here holds
  one.
- A length or a count is in scalar values, and is unsigned decimal.
- Anything else, a direction, an outcome or a reason, is an upper case identifier, and a yes or no
  is `true` or `false`.

Writing a text as scalar values keeps it apart from how any language holds one, UTF-8, UTF-16 or
bytes, and lets a line write a tab, a line break, U+0000 or a `;` as readily as a letter. What
only one language's strings can hold, such as half of a surrogate pair in a Java `String`, is not
a vector here, and is tested in that language.
