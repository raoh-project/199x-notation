package notation199x

import (
	"fmt"
	"os"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"
)

// The four forms against Unicode's own NormalizationTest.txt, in full. Each data line is five
// columns, source, NFC, NFD, NFKC and NFKD, and the file states what conformance is:
// c2 == toNFC(c1) == toNFC(c2) == toNFC(c3) and c4 == toNFC(c4) == toNFC(c5);
// c3 == toNFD(c1) == toNFD(c2) == toNFD(c3) and c5 == toNFD(c4) == toNFD(c5);
// c4 == toNFKC and c5 == toNFKD of all five. A code point no line of Part 1 names is its own
// normalization in every form.
func TestNormalizationAnswersEveryLineOfTheConformanceTest(t *testing.T) {
	data, err := os.ReadFile(repositoryFile(t, "ucd/18.0.0/NormalizationTest.txt"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(string(data), "\n")
	if lines[0] != "# NormalizationTest-18.0.0.txt" {
		t.Fatalf("the file opens with %q", lines[0])
	}
	named := make([]bool, utf8.MaxRune+1)
	partOne := false
	checked, failed := 0, 0
	for n, text := range lines {
		if hash := strings.IndexByte(text, '#'); hash >= 0 {
			text = text[:hash]
		}
		text = strings.TrimSpace(text)
		if strings.HasPrefix(text, "@") {
			partOne = text == "@Part1"
			continue
		}
		if text == "" {
			continue
		}
		columns := strings.Split(text, ";")
		var c [6]string
		for i := 1; i <= 5; i++ {
			c[i] = decodeHex(t, columns[i-1])
		}
		if partOne {
			r, _ := utf8.DecodeRuneInString(c[1])
			named[r] = true
		}
		checked++
		for i := 1; i <= 5; i++ {
			nfc, nfd := c[4], c[5]
			if i <= 3 {
				nfc, nfd = c[2], c[3]
			}
			for form, want := range map[Form]string{NFC: nfc, NFD: nfd, NFKC: c[4], NFKD: c[5]} {
				if got := Normalize(form, c[i]); got != want {
					failed++
					if failed <= 20 {
						t.Errorf("line %d: %v(c%d %s) is %s, not %s", n+1, form, i, shown(c[i]), shown(got), shown(want))
					}
				}
			}
		}
	}
	if checked < 10_000 {
		t.Fatalf("the file's data lines were read: %d", checked)
	}
	for r := rune(0); r <= utf8.MaxRune; r++ {
		if named[r] || r >= 0xD800 && r <= 0xDFFF {
			continue
		}
		alone := string(r)
		for _, form := range []Form{NFC, NFD, NFKC, NFKD} {
			if Normalize(form, alone) != alone {
				failed++
				if failed <= 20 {
					t.Errorf("%v changes U+%04X, which Part 1 does not name", form, r)
				}
			}
		}
	}
	if failed > 0 {
		t.Errorf("%d failed", failed)
	}
}

func decodeHex(t *testing.T, field string) string {
	t.Helper()
	var out strings.Builder
	for _, token := range strings.Fields(field) {
		value, err := strconv.ParseUint(token, 16, 32)
		if err != nil {
			t.Fatal(err)
		}
		out.WriteRune(rune(value))
	}
	return out.String()
}

// A text normalized one combining run at a time is the text normalized whole: a bound that is
// the answer's length takes it, and one less does not.
func TestABoundIsHeldOnTheAnswer(t *testing.T) {
	for _, text := range []string{"", "a", "Å", "Ǻ", "ﬃ", "㌀", "가", "\U0001D15E", "ȩ́́x"} {
		for _, form := range []Form{NFC, NFD, NFKC, NFKD} {
			whole := Normalize(form, text)
			length := utf8.RuneCountInString(whole)
			if got, ok := NormalizeWithin(form, text, length); !ok || got != whole {
				t.Errorf("%v of %s within %d is %s, %v", form, shown(text), length, shown(got), ok)
			}
			if length > 0 {
				if _, ok := NormalizeWithin(form, text, length-1); ok {
					t.Errorf("%v of %s is within %d", form, shown(text), length-1)
				}
			}
			if _, ok := NormalizeWithin(form, text, -1); ok {
				t.Errorf("%v of %s is within a negative bound", form, shown(text))
			}
		}
	}
}

// A starter and a combining run of a million marks, held to a bound of ten, is found past the bound
// before the run is held: what is made is the room for the bound and the marks that may compose,
// not for the run.
func TestABoundedNormalizationHoldsNoMoreThanItsBound(t *testing.T) {
	text := "a" + strings.Repeat("\u0301", 1_000_000)
	// The composition table is made the first time a text is normalized, once for the program.
	Normalize(NFC, "a\u0301")
	for _, form := range []Form{NFC, NFD, NFKC, NFKD} {
		var before, after runtime.MemStats
		runtime.ReadMemStats(&before)
		_, ok := NormalizeWithin(form, text, 10)
		runtime.ReadMemStats(&after)
		if ok {
			t.Errorf("%v of the run is within 10", form)
		}
		if made := after.TotalAlloc - before.TotalAlloc; made > 64<<10 {
			t.Errorf("%v made %d bytes", form, made)
		}
	}
}

func ExampleNormalize() {
	fmt.Printf("%q\n", Normalize(NFC, "Å"))
	fmt.Printf("%q\n", Normalize(NFKD, "ﬃ"))
	// Output:
	// "Å"
	// "ffi"
}
