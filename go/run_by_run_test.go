package notation199x

import (
	"math/rand"
	"strings"
	"testing"
	"unicode/utf8"
	"unsafe"
)

// Normalization keeps the text as it is where it reads stable starters, and runs the algorithm only
// from the stable starter before a code point that is not one up to the next stable starter. That
// is right only where the answer is what the algorithm gives over the whole text, so the two are
// held against each other here, in every form and within every bound around the answer's length.
// The texts mix stable starters of several scripts with marks, composites that decompose in one
// form and not another, compatibility characters, Hangul jamo and the kana voicing marks, so that
// a text goes in and out of the algorithm many times.
func TestRunByRunIsTheAlgorithmOverTheWholeText(t *testing.T) {
	alphabet := []rune{
		'a', 'e', 'A', ' ', '.', 0x00E9, 0x00C7, // stable in a canonical form
		0x3042, 0x304B, 0x30AB, 0x65E5, 0x672C, // kana and ideographs
		0xAC00, 0xAC01, 0xD55C, // Hangul syllables
		0x20B9F, 0x1F600, // stable past the basic plane
		0x0300, 0x0301, 0x0323, 0x0327, 0x05B0, // marks
		0x3099, 0x309A, 0x309B, // kana voicing marks
		0x1100, 0x1161, 0x11A8, // Hangul L, V and T
		0xFB01, 0x3231, 0xFF76, 0xFF9E, 0x00A0, 0x2126, // compatibility characters, a singleton
		0x0B47, 0x0B3E, 0x1D15E, 0x0344, // a starter second, marks that decompose
	}
	random := rand.New(rand.NewSource(1999))
	for n := 0; n < 20000; n++ {
		var text strings.Builder
		for i := random.Intn(40); i > 0; i-- {
			r := alphabet[random.Intn(len(alphabet))]
			times := 1
			if random.Intn(4) == 0 {
				times += random.Intn(6)
			}
			for ; times > 0; times-- {
				text.WriteRune(r)
			}
		}
		s := text.String()
		for _, form := range []Form{NFC, NFD, NFKC, NFKD} {
			whole, _ := normalizeWhole(form, s, -1)
			if got := Normalize(form, s); got != whole {
				t.Fatalf("%v of %s is %s, not %s", form, shown(s), shown(got), shown(whole))
			}
			length := utf8.RuneCountInString(whole)
			for longest := max(0, length-2); longest <= length+1; longest++ {
				got, ok := NormalizeWithin(form, s, longest)
				if ok != (longest >= length) || ok && got != whole {
					t.Fatalf("%v of %s within %d is %s, %v", form, shown(s), longest, shown(got), ok)
				}
			}
		}
	}
}

// Text that is its own normalization, or that a case mapping leaves as it is, is answered with
// itself, whether or not the algorithm went over part of it.
func TestTextNothingChangesIsAnsweredWithItself(t *testing.T) {
	same := func(a, b string) bool { return len(a) == len(b) && unsafe.StringData(a) == unsafe.StringData(b) }
	japanese := strings.Repeat("日本語のテキスト、ガギグ。", 10)
	korean := strings.Repeat("한국어 텍스트", 10)
	latin := strings.Repeat("Renée Ångström à l'école", 10)
	marked := strings.Repeat("abḉdef", 10)
	for _, each := range []struct {
		form Form
		text string
	}{{NFC, japanese}, {NFC, korean}, {NFC, latin}, {NFKC, japanese}, {NFD, marked}} {
		if !same(Normalize(each.form, each.text), each.text) {
			t.Errorf("%v of %s is not the text itself", each.form, shown(each.text))
		}
	}
	if got, want := Normalize(NFC, japanese+"が"+japanese), japanese+"が"+japanese; got != want {
		t.Errorf("NFC is %s", shown(got))
	}
	for _, s := range []string{"日本語のテキスト", "straße", "abc def 123"} {
		if !same(Lowercase(s), s) {
			t.Errorf("Lowercase of %s is not the text itself", shown(s))
		}
	}
	for _, s := range []string{"日本語のテキスト", "ABC DEF 123"} {
		if !same(Uppercase(s), s) {
			t.Errorf("Uppercase of %s is not the text itself", shown(s))
		}
	}
}

// normalizeWhole is the algorithm over the whole of s, from its start, whatever the text.
//
// The three steps are taken one combining run at a time, as the text is read: each code point is
// decomposed as it arrives, the marks after a starter are held until the next starter, and then
// they are put in canonical order and, in a composing form, composed into it. Canonical ordering
// never moves a mark past a starter, and composition joins a starter only to the marks after it
// or, where nothing is between them, to the starter after it, so a run settled when the next
// starter arrives is settled as the whole text's algorithm would settle it. What is held at once
// is one run's marks, never the decomposition of the whole text.
func normalizeWhole(form Form, s string, longest int) (string, bool) {
	c := newComposing(form.facts(), longest)
	if _, ok := c.run(s, 0, len(s), 0); !ok {
		return "", false
	}
	return string(c.out), true
}
