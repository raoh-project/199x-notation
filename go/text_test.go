package notation199x

import "testing"

func TestInvalidUTF8AtIsWhereTheFirstByteThatIsNoCharacterIs(t *testing.T) {
	for text, at := range map[string]int{
		"":                 -1,
		"abc":              -1,
		"é😀�":              -1,
		"\xff":             0,
		"ab\xff":           2,
		"é\xed\xa0\x80":    2, // a surrogate in UTF-8's form
		"😀\xf0\x9f\x98":    4, // a character cut short
		"\xc0\x80":         0, // an overlong NUL
		"\xf4\x90\x80\x80": 0, // past U+10FFFF
	} {
		if got := InvalidUTF8At(text); got != at {
			t.Errorf("InvalidUTF8At(%q) is %d, not %d", text, got, at)
		}
	}
}

func TestATextIsComparedByScalarValue(t *testing.T) {
	// U+FF61 is above U+1F600's first UTF-16 unit and below the scalar value.
	if Compare("｡", "\U0001F600") != -1 || Compare("\U0001F600", "｡") != 1 {
		t.Error("a scalar value past U+FFFF is below one in U+E000..U+FFFF")
	}
	if Compare("ab", "abc") != -1 || Compare("abc", "abc") != 0 {
		t.Error("a prefix is below")
	}
}

func TestTheSigmaIsFinalOnlyAtTheEndOfACasedRun(t *testing.T) {
	for text, lower := range map[string]string{
		"ΟΣ":   "ος",
		"ΟΣΑ":  "οσα",
		"Σ":    "σ",
		"ΟΣ.":  "ος.",
		"Ο'Σ'": "ο'ς'",
		"ͅΣ":   "ͅσ",
		"ΟΣ­Α": "οσ­α",
	} {
		if got := Lowercase(text); got != lower {
			t.Errorf("Lowercase(%q) is %q, not %q", text, got, lower)
		}
	}
	if got, ok := UppercaseWithin("straße", 6); ok || got != "" {
		t.Errorf("STRASSE is within 6: %q", got)
	}
	if _, ok := LowercaseWithin("", -1); ok {
		t.Error("the empty text is within a negative bound")
	}
}

func TestALeapSecondIsRefusedForWhatItIs(t *testing.T) {
	for text, answer := range map[string]TemporalAnswer{
		"2016-12-31T23:59:60Z":                LeapSecond,
		"2016-12-31T23:59:59Z":                Admitted,
		"2016-12-31T24:00:60Z":                Malformed,
		"+1000000000-12-31T23:59:60Z":         LeapSecond,
		"+1000000000-12-31T23:59:60-00:00:01": Malformed,
	} {
		if got := CheckTemporal(Instant, text); got != answer {
			t.Errorf("CheckTemporal(Instant, %q) is %d, not %d", text, got, answer)
		}
	}
	if got := CheckTemporal(DateTime, "2016-12-31T23:59:60"); got != Malformed {
		t.Errorf("a date-time with second 60 is %d", got)
	}
}
