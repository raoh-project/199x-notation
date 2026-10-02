package notation199x

import (
	"strings"
	"testing"
)

// A number that is none of a type's constants is no value of it: a function handed one panics,
// rather than reading it as one of the values, and String says what it is.
func TestANumberThatIsNoneOfTheConstantsIsNoValue(t *testing.T) {
	for name, call := range map[string]func(){
		"Normalize":       func() { Normalize(Form(4), "a") },
		"Normalize ASCII": func() { Normalize(Form(99), "") },
		"NormalizeWithin": func() { NormalizeWithin(Form(4), "a", -1) },
		"CheckTemporal":   func() { CheckTemporal(TemporalKind(5), "2026-10-02") },
		"Most":            func() { PatternLimit(3).Most() },
	} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%s took a number that is none of the constants", name)
				}
			}()
			call()
		}()
	}
	for got, want := range map[string]string{
		Form(4).String():                 "Form(4)",
		NFKD.String():                    "NFKD",
		TemporalKind(5).String():         "TemporalKind(5)",
		Instant.String():                 "Instant",
		TemporalAnswer(3).String():       "TemporalAnswer(3)",
		LeapSecond.String():              "LeapSecond",
		PatternLimit(3).String():         "PatternLimit(3)",
		MachineStates.String():           "MachineStates",
		PatternRefusal(12).String():      "PatternRefusal(12)",
		AnAnchorThisCannotPlace.String(): "AnAnchorThisCannotPlace",
	} {
		if got != want {
			t.Errorf("%q is not %q", got, want)
		}
	}
}

// A copy of a Pattern is the same pattern, matched with what the first one built.
func TestACopyOfAPatternIsTheSamePattern(t *testing.T) {
	p := ReadPattern("(?:a|b)*c").(*Pattern)
	p.Matches("abc")
	q := *p
	if q.compiled != p.compiled || !q.Matches("bbac") || q.Matches("abca") {
		t.Error("a copy of a pattern is another pattern")
	}
	defer func() {
		if recover() == nil {
			t.Error("a Pattern ReadPattern did not make was matched")
		}
	}()
	var none Pattern
	none.Matches("a")
}

// allocations is how many times f allocates, on average.
func allocations(f func()) float64 {
	return testing.AllocsPerRun(20, f)
}

// What a function allocates is its answer and room that does not grow with the text: no character
// costs an allocation. Held by giving each function a text and one ten times as long.
func TestNoCharacterCostsAnAllocation(t *testing.T) {
	for _, each := range []struct {
		name string
		call func(text string)
		text string
		most float64
	}{
		{"Lowercase of text it does not change", func(s string) { Lowercase(s) }, "日本語のテキスト。", 0},
		{"Lowercase", func(s string) { Lowercase(s) }, "Hello, World. Ça été. ", 1},
		{"Uppercase", func(s string) { Uppercase(s) }, "straße ος ", 1},
		{"LowercaseWithin", func(s string) { LowercaseWithin(s, 1<<30) }, "ΟΣ ΟΣΑ ", 1},
		{"Normalize NFC of text past the limit", func(s string) { Normalize(NFC, s) }, "Ça été un café crème. 日本語です。", 3},
		{"Normalize NFD", func(s string) { Normalize(NFD, s) }, "Ça été un café crème. 가각. ", 4},
		{"Normalize NFKC", func(s string) { Normalize(NFKC, s) }, "ﬃ ㌀ ① ", 4},
		{"NormalizeWithin", func(s string) { NormalizeWithin(NFKD, s, 1<<30) }, "ﬃ Å ", 4},
		{"Normalize of text below the limit", func(s string) { Normalize(NFC, s) }, "plain text ", 0},
		{"CheckTemporal", func(s string) { CheckTemporal(Instant, s) }, "2026-10-02T12:34:56.789+09:00", 0},
		{"InvalidUTF8At", func(s string) { InvalidUTF8At(s) }, "é😀a", 0},
		{"ScalarCount", func(s string) { ScalarCount(s) }, "é😀a", 0},
	} {
		longer := strings.Repeat(each.text, 10)
		short := allocations(func() { each.call(each.text) })
		long := allocations(func() { each.call(longer) })
		if short > each.most || long > short {
			t.Errorf("%s allocates %v times, and %v for ten times the text", each.name, short, long)
		}
	}
	if raceEnabled {
		return
	}
	read := ReadPattern(`[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}|日本語+`).(*Pattern)
	subject := "someone.with-a-long-name@example.co.jp"
	read.Matches(subject)
	read.Matches("日本語語")
	for _, s := range []string{subject, "日本語語語"} {
		if n := allocations(func() { read.Matches(s) }); n != 0 {
			t.Errorf("a match of %q, whose sets are kept, allocates %v times", s, n)
		}
	}
}
