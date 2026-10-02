package notation199x

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// The vectors in the repository's suite directory and the database files in its ucd directory
// are outside this module: a module made from a subdirectory holds what is under it, so a copy of
// the module that go fetched has neither. A test that reads them runs in a checkout, skips where
// they are not there, and fails instead where NOTATION199X_REQUIRE_SUITE is set, as CI sets it, so
// that a checkout missing them is not taken for a module without them.
const requireSuite = "NOTATION199X_REQUIRE_SUITE"

// repositoryFile is the path of name under the repository's root, or a skip where the file is not
// there and the environment does not require it.
func repositoryFile(t *testing.T, name string) string {
	t.Helper()
	path := filepath.Join("..", filepath.FromSlash(name))
	if _, err := os.Stat(path); errors.Is(err, fs.ErrNotExist) {
		if os.Getenv(requireSuite) != "" {
			t.Fatalf("%s is missing, and %s is set: the tests run in go/ of a checkout", path, requireSuite)
		}
		t.Skipf("%s is outside this module, and is read only in a checkout of the repository", path)
	}
	return path
}

// line is one line of vectors, and which of its fields have been read.
type line struct {
	where  string
	fields []string
	read   []bool
	err    error
}

func (l *line) take(i int) string {
	l.read[i] = true
	return l.fields[i]
}

func (l *line) wrong(i int, what string) {
	if l.err == nil {
		l.err = fmt.Errorf("%s: field %d is %q, not %s", l.where, i+1, l.fields[i], what)
	}
}

// empty reads a field a line leaves empty where it asserts nothing there.
func (l *line) empty(i int) {
	if l.take(i) != "" {
		l.wrong(i, "empty")
	}
}

var scalarHex = regexp.MustCompile(`^[0-9A-F]{4,6}$`)

// text reads a field as the text it writes: scalar values in hex, separated by spaces.
func (l *line) text(i int) string {
	field := l.take(i)
	if field == "" {
		return ""
	}
	var text strings.Builder
	for _, each := range strings.Fields(field) {
		value, err := strconv.ParseUint(each, 16, 32)
		if !scalarHex.MatchString(each) || err != nil || value > 0x10FFFF || value >= 0xD800 && value <= 0xDFFF {
			l.wrong(i, "a text of scalar values in hex")
			return ""
		}
		text.WriteRune(rune(value))
	}
	return text.String()
}

// scalar reads a field as the one scalar value it writes.
func (l *line) scalar(i int) rune {
	text := []rune(l.text(i))
	if len(text) != 1 {
		l.wrong(i, "one scalar value")
		return 0
	}
	return text[0]
}

var unsignedDecimal = regexp.MustCompile(`^(0|[1-9][0-9]{0,17})$`)

// number reads a field as an unsigned decimal.
func (l *line) number(i int) int {
	field := l.take(i)
	if !unsignedDecimal.MatchString(field) {
		l.wrong(i, "an unsigned decimal")
		return 0
	}
	n, _ := strconv.Atoi(field)
	return n
}

// numberOrNothing reads a field as an unsigned decimal, or -1 where it is empty.
func (l *line) numberOrNothing(i int) int {
	if l.fields[i] == "" {
		l.empty(i)
		return -1
	}
	return l.number(i)
}

// oneOf reads a field as one of names.
func (l *line) oneOf(i int, names ...string) string {
	field := l.take(i)
	for _, name := range names {
		if field == name {
			return field
		}
	}
	l.wrong(i, fmt.Sprintf("one of %v", names))
	return ""
}

// oneOfOrNothing reads a field as one of names, or "" where it is empty.
func (l *line) oneOfOrNothing(i int, names ...string) string {
	if l.fields[i] == "" {
		l.empty(i)
		return ""
	}
	return l.oneOf(i, names...)
}

// yesOrNo reads a field as true or false.
func (l *line) yesOrNo(i int) bool {
	switch l.take(i) {
	case "true":
		return true
	case "false":
		return false
	}
	l.wrong(i, "true or false")
	return false
}

// eachLine holds the implementation to every line of name in the suite, as suite/README.md
// states the format: check reads every field of a line and answers what is wrong on it, or "".
// A line is read whole or not at all, and a field no check reads fails the test, so nothing
// written in a file goes unchecked.
func eachLine(t *testing.T, name string, fields int, check func(l *line) string) {
	t.Helper()
	data, err := os.ReadFile(repositoryFile(t, "suite/"+name))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSuffix(string(data), "\n"), "\n")
	sourced := false
	vectors := 0
	for n, text := range lines {
		if text == "# Source:" && n+1 < len(lines) && strings.HasPrefix(lines[n+1], "#   ") {
			sourced = true
		}
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		where := fmt.Sprintf("%s:%d", name, n+1)
		if !sourced {
			t.Fatalf("%s names no source before its first vector", name)
		}
		split := strings.Split(text, ";")
		if len(split) != fields {
			t.Fatalf("%s has %d fields, not %d", where, len(split), fields)
		}
		for i := range split {
			split[i] = strings.TrimSpace(split[i])
		}
		l := &line{where: where, fields: split, read: make([]bool, fields)}
		said := check(l)
		for i, read := range l.read {
			if !read {
				t.Fatalf("%s: field %d is not read", where, i+1)
			}
		}
		if l.err != nil {
			t.Fatal(l.err)
		}
		if said != "" {
			t.Errorf("%s: %s", where, said)
		}
		vectors++
	}
	if vectors == 0 {
		t.Fatalf("%s holds no vectors", name)
	}
}

// shown is text as a message shows it: printable ASCII as itself, and every other scalar value as
// <U+XXXX>.
func shown(text string) string {
	var out strings.Builder
	out.WriteByte('"')
	for _, r := range text {
		if r >= 0x20 && r < 0x7F {
			out.WriteRune(r)
		} else {
			fmt.Fprintf(&out, "<U+%04X>", r)
		}
	}
	out.WriteByte('"')
	return out.String()
}

func TestCaseConversionAnswersEveryLineOfTheSuite(t *testing.T) {
	eachLine(t, "case.txt", 5, func(l *line) string {
		text := l.text(0)
		lower := l.oneOf(1, "LOWER", "UPPER") == "LOWER"
		bound := l.numberOrNothing(2)
		past := l.oneOf(3, "MAPPED", "PAST") == "PAST"
		var mapped string
		if past {
			l.empty(4)
		} else {
			mapped = l.text(4)
		}
		var answer string
		within := true
		switch {
		case bound < 0 && lower:
			answer = Lowercase(text)
		case bound < 0:
			answer = Uppercase(text)
		case lower:
			answer, within = LowercaseWithin(text, bound)
		default:
			answer, within = UppercaseWithin(text, bound)
		}
		if bound < 0 && past {
			return "a conversion without a bound is never past one"
		}
		if within == past || (within && answer != mapped) {
			return fmt.Sprintf("%s is %s (within: %v), not %s (past: %v)", shown(text), shown(answer), within, shown(mapped), past)
		}
		return ""
	})
}

var formsByName = map[string]Form{"NFC": NFC, "NFD": NFD, "NFKC": NFKC, "NFKD": NFKD}

func TestNormalizationWithinABoundAnswersEveryLineOfTheSuite(t *testing.T) {
	eachLine(t, "normalization-bound.txt", 5, func(l *line) string {
		text := l.text(0)
		form := formsByName[l.oneOf(1, "NFC", "NFD", "NFKC", "NFKD")]
		bound := l.number(2)
		past := l.oneOf(3, "NORMALIZED", "PAST") == "PAST"
		var normalized string
		if past {
			l.empty(4)
		} else {
			normalized = l.text(4)
		}
		answer, within := NormalizeWithin(form, text, bound)
		if within == past || (within && answer != normalized) {
			return fmt.Sprintf("%s in %v within %d is %s (within: %v)", shown(text), form, bound, shown(answer), within)
		}
		return ""
	})
}

func TestWhiteSpaceIsTheSetTheSuiteLists(t *testing.T) {
	listed := map[rune]bool{}
	eachLine(t, "white-space.txt", 1, func(l *line) string {
		listed[l.scalar(0)] = true
		return ""
	})
	for r := rune(0); r <= 0x10FFFF; r++ {
		if r >= 0xD800 && r <= 0xDFFF {
			continue
		}
		if IsWhiteSpace(r) != listed[r] {
			t.Errorf("U+%04X is white space: %v, and the suite lists it: %v", r, IsWhiteSpace(r), listed[r])
		}
	}
}

func TestScalarLengthAnswersEveryLineOfTheSuite(t *testing.T) {
	eachLine(t, "scalar-length.txt", 2, func(l *line) string {
		text := l.text(0)
		length := l.number(1)
		if ScalarCount(text) != length {
			return fmt.Sprintf("%s is %d long, not %d", shown(text), ScalarCount(text), length)
		}
		return ""
	})
}

func TestScalarOrderAnswersEveryLineOfTheSuite(t *testing.T) {
	orders := map[string]int{"LESS": -1, "EQUAL": 0, "GREATER": 1}
	eachLine(t, "scalar-order.txt", 3, func(l *line) string {
		a := l.text(0)
		b := l.text(1)
		order := orders[l.oneOf(2, "LESS", "EQUAL", "GREATER")]
		if Compare(a, b) != order || Compare(b, a) != -order {
			return fmt.Sprintf("%s against %s is %d, not %d", shown(a), shown(b), Compare(a, b), order)
		}
		return ""
	})
}

func TestTemporalTextAnswersEveryLineOfTheSuite(t *testing.T) {
	kinds := map[string]TemporalKind{
		"DATE": Date, "TIME": Time, "DATETIME": DateTime, "OFFSET_DATETIME": OffsetDateTime, "INSTANT": Instant,
	}
	eachLine(t, "temporal.txt", 3, func(l *line) string {
		kind := kinds[l.oneOf(0, "DATE", "TIME", "DATETIME", "OFFSET_DATETIME", "INSTANT")]
		text := l.text(1)
		admitted := l.oneOf(2, "ADMITTED", "REFUSED") == "ADMITTED"
		answer := CheckTemporal(kind, text)
		if (answer == Admitted) != admitted {
			return fmt.Sprintf("%s as kind %d is %d", shown(text), kind, answer)
		}
		return ""
	})
}
