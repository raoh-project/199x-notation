package notation199x

import (
	"slices"
	"strings"
	"sync"
	"testing"
)

func refusedOf(t *testing.T, pattern string) PatternRefused {
	t.Helper()
	refused, ok := ReadPattern(pattern).(PatternRefused)
	if !ok {
		t.Fatalf("%q is %#v, not refused", pattern, ReadPattern(pattern))
	}
	return refused
}

// What makes text no pattern is told apart by what an author wrote.
func TestEachRefusalIsForWhatWasWritten(t *testing.T) {
	for pattern, why := range map[string]PatternRefusal{
		"(a":           SomethingUnclosed,
		"[a":           SomethingUnclosed,
		"a)":           SomethingUnclosed,
		"*a":           SomethingUnclosed,
		"a{6,2}":       ACountThisCannotRead,
		"a{":           ACountThisCannotRead,
		"[b-a]":        ACountThisCannotRead,
		"\\y":          AnEscapeThisDoesNotRead,
		"\\x{110000}":  AnEscapeThisDoesNotRead,
		"[a-\\d]":      AnEscapeThisDoesNotRead,
		"\\꟝":          AnEscapeThisDoesNotRead,
		"(?=a)b":       AGroupTheGrammarDoesNotHave,
		"(?<name>a)":   AGroupTheGrammarDoesNotHave,
		"(?i)a":        AGroupTheGrammarDoesNotHave,
		"(a)\\1":       ABackReference,
		"\\k<a>":       ABackReference,
		"\\p{Alpha}":   ACharacterProperty,
		"\\bword\\b":   ABoundary,
		"\\Qa+b\\E":    AQuotation,
		"[a-z&&[^bc]]": AClassOfClasses,
		"[a[bc]]":      AClassOfClasses,
		"a{2,6}+":      APossessiveRepetition,
		"(?:|a)++":     APossessiveRepetition,
		"(a|)^b":       AnAnchorThisCannotPlace,
		"(^a)*":        AnAnchorThisCannotPlace,
		"a$b":          AnAnchorThisCannotPlace,
	} {
		if got := refusedOf(t, pattern).Why; got != why {
			t.Errorf("%q is refused as %d, not %d", pattern, got, why)
		}
	}
}

// A pair of \u escapes is the one character it encodes, worked out before the character is asked
// whether it is a symbol; half of a pair is no symbol however it is written, in a class or out of
// one.
func TestAPairOfUnicodeEscapesIsOneCharacter(t *testing.T) {
	for _, pattern := range []string{`😀`, `[😀]`, `[😀-🙏]`, `\x{1F600}`, "\U0001F600"} {
		read, ok := ReadPattern(pattern).(*Pattern)
		if !ok {
			t.Fatalf("%q is %#v", pattern, ReadPattern(pattern))
		}
		if !read.Matches("\U0001F600") || read.Matches("\U0001F601"+"\U0001F601") {
			t.Errorf("%q does not accept U+1F600 alone", pattern)
		}
	}
	for _, pattern := range []string{`\uD83D`, `\uDE00`, `\uDE00\uD83D`, `\uD83DA`, `\uD83D\uD83D`, `\x{D800}`, `[\uD800]`, `[a-\x{DFFF}]`} {
		if got := refusedOf(t, pattern).Why; got != ACharacterNoStringHolds {
			t.Errorf("%q is refused as %d", pattern, got)
		}
	}
	// A high escape with a malformed escape after it spells the high surrogate, and is refused for
	// that rather than for the escape after it.
	if got := refusedOf(t, `\uD83D\u00G0`); got.Why != ACharacterNoStringHolds || got.From != 0 {
		t.Errorf(`\uD83D\u00G0 is %#v`, got)
	}
}

// Bytes that are not UTF-8 are no character, wherever the reader takes a character from the text:
// a refusal from where they are, as half of a surrogate pair is in a Java string.
func TestBytesThatAreNotUTF8AreRefusedWhereverACharacterIsTaken(t *testing.T) {
	for _, place := range []struct {
		pattern string
		at      int
	}{
		{"%s", 0}, {"ab%scd", 2}, {"(?:a|%s)", 5}, {"%s+", 0}, {"[%s]", 1}, {"[^%s]", 2},
		{"[a%sb]", 2}, {"[%s-z]", 1}, {"[a-%s]", 1}, {"\\%s", 0}, {"[\\%s]", 1},
	} {
		for _, bad := range []string{"\xff", "\xed\xa0\x80", "\xc3"} {
			pattern := strings.Replace(place.pattern, "%s", bad, 1)
			refused := refusedOf(t, pattern)
			if refused.Why != ACharacterNoStringHolds || refused.From != place.at {
				t.Errorf("%q is %#v", pattern, refused)
			}
		}
	}
}

// Where a refusal points and what it quotes are in bytes of the text, and quote characters whole.
func TestARefusalQuotesTheConstructInBytes(t *testing.T) {
	for pattern, want := range map[string]PatternRefused{
		"é(?😀":         {Why: AGroupTheGrammarDoesNotHave, From: 2, Construct: "(?😀"},
		"éé\\p{L}":     {Why: ACharacterProperty, From: 4, Construct: "\\p"},
		"é{3,1}":       {Why: ACountThisCannotRead, From: 2, Construct: "{3,1}"},
		"é)":           {Why: SomethingUnclosed, From: 2, Construct: ")"},
		"(é":           {Why: SomethingUnclosed, From: 3, Construct: ""},
		"a(a|)^b":      {Why: AnAnchorThisCannotPlace, From: 0, Construct: "a(a|)^b"},
		"[😀\\x{D800}]": {Why: ACharacterNoStringHolds, From: 5, Construct: "\\x{D800}"},
	} {
		if got := refusedOf(t, pattern); got != want {
			t.Errorf("%q is %#v, not %#v", pattern, got, want)
		}
	}
}

// A limit is noted where it is met and the reading goes on, so text that is no pattern after it is
// refused, and the first limit met in the text is the answer.
func TestALimitIsAnsweredOnlyOfAPattern(t *testing.T) {
	if got := refusedOf(t, "a{134217728}("); got.Why != SomethingUnclosed {
		t.Errorf("a{134217728}( is %#v", got)
	}
	beyond, ok := ReadPattern("é{134217728}" + strings.Repeat("(", 201) + strings.Repeat(")", 201)).(PatternBeyond)
	if !ok || beyond != (PatternBeyond{Limit: RepetitionCount, From: 3, Construct: "134217728"}) {
		t.Errorf("the first limit met is %#v", beyond)
	}
	if _, ok := ReadPattern("a{134217727}").(PatternBeyond); !ok {
		t.Errorf("a{134217727} is within the count and past the states")
	}
}

// Groups are read with a stack of their own, and so are the anchors placed, so text nested far
// past any limit is read to its end.
func TestTextNestedAsDeeplyAsItIsLongIsReadToItsEnd(t *testing.T) {
	deep := 1 << 20
	beyond, ok := ReadPattern(strings.Repeat("(", deep) + "^a$" + strings.Repeat(")", deep)).(PatternBeyond)
	if !ok || beyond.Limit != NestingDepth || beyond.From != 200 {
		t.Errorf("a pattern nested %d deep is %#v", deep, beyond)
	}
	if got := refusedOf(t, strings.Repeat("(", deep)+"a|)^b"+strings.Repeat(")", deep-1)); got.Why != AnAnchorThisCannotPlace {
		t.Errorf("an anchor nested %d deep is %#v", deep, got)
	}
	if got := refusedOf(t, strings.Repeat("(", deep)); got.Why != SomethingUnclosed {
		t.Errorf("%d groups left open are %#v", deep, got)
	}
}

// A subject holding bytes that are not UTF-8 is no text, and is accepted by nothing.
func TestASubjectThatIsNotUTF8IsAcceptedByNothing(t *testing.T) {
	for _, pattern := range []string{".*", "[^a]*", "(?:.|\\n)*", "\\W"} {
		read := ReadPattern(pattern).(*Pattern)
		for _, subject := range []string{"\xff", "a\xed\xa0\x80", "\xc3"} {
			if read.Matches(subject) {
				t.Errorf("%q accepts %q", pattern, subject)
			}
		}
	}
	if !ReadPattern(".").(*Pattern).Matches("�") {
		t.Errorf("U+FFFD written as itself is a character")
	}
}

// A pattern is matched from several goroutines at once, each walk in room of its own.
func TestAPatternIsMatchedFromSeveralGoroutinesAtOnce(t *testing.T) {
	read := ReadPattern("(?:a|b)*abb").(*Pattern)
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 1000; i++ {
				if !read.Matches("ababb") || read.Matches("ababa") {
					t.Error("a walk was disturbed by another")
					return
				}
			}
		}()
	}
	wg.Wait()
}

// A machine at the limit of states is walked a character at a time, every state it may be in at
// once: (?:a|a)... written out, with a subject that keeps the walk in all of them.
func BenchmarkAMatchAtTheLimitOfStates(b *testing.B) {
	read, ok := ReadPattern("(?:a?){49998}").(*Pattern)
	if !ok {
		b.Fatalf("%#v", ReadPattern("(?:a?){49998}"))
	}
	subject := strings.Repeat("a", 100)
	read.Matches("")
	b.ResetTimer()
	for range b.N {
		read.Matches(subject)
	}
}

func BenchmarkAMatchOfAFewStates(b *testing.B) {
	read := ReadPattern("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}").(*Pattern)
	subject := "someone.with-a-long-name@example.co.jp"
	for range b.N {
		read.Matches(subject)
	}
}

// Which sets a walk keeps, and whether it keeps any, changes how fast it is and no answer: every
// pattern accepts the same subjects with nothing kept, with so little kept that it is forgotten
// every few characters, and with the room a walk is given.
func TestWhatAWalkKeepsChangesNoAnswer(t *testing.T) {
	pieces := []string{"a", "b", "é", "😀", ".", "[ab]", "[^a]", "\\w", "(?:a|b)", "(?:ab|a)", "a*", "b+", "(?:a|é)?", "[a-é]{1,3}", "(?:😀|.)*", "^", "$"}
	letters := []rune{'a', 'b', 'é', '😀', 'c', '\n'}
	rng := uint32(1)
	next := func(n int) int {
		rng = rng*1664525 + 1013904223
		return int(rng>>8) % n
	}
	defer func(was int) { knownBytes = was }(knownBytes)
	for i := 0; i < 2000; i++ {
		var pattern strings.Builder
		for k := next(6); k >= 0; k-- {
			pattern.WriteString(pieces[next(len(pieces))])
		}
		var subjects []string
		for k := 0; k < 8; k++ {
			var subject strings.Builder
			for m := next(12); m > 0; m-- {
				subject.WriteRune(letters[next(len(letters))])
			}
			subjects = append(subjects, subject.String())
		}
		var answers [][]bool
		for _, room := range []int{0, 1500, 2 << 20} {
			knownBytes = room
			read, ok := ReadPattern(pattern.String()).(*Pattern)
			if !ok {
				break
			}
			var these []bool
			for round := 0; round < 2; round++ {
				for _, subject := range subjects {
					these = append(these, read.Matches(subject))
				}
			}
			answers = append(answers, these)
		}
		for _, these := range answers[min(1, len(answers)):] {
			if !slices.Equal(these, answers[0]) {
				t.Fatalf("%q answers %v with nothing kept and %v otherwise, for %q", pattern.String(), answers[0], these, subjects)
			}
		}
	}
}

// A room that gave up keeping sets tries again once it has read what it waits for, keeps sets for
// subjects whose sets are looked up again, and waits twice as long after a try that gives up
// again; the answers are the same throughout. A room is kept between matches in the machine's
// pool, so one that gave up for good would walk every later subject a state at a time.
func TestAGivenUpRoomTriesAgainAndWaitsLongerAfterEachTryThatFails(t *testing.T) {
	read := ReadPattern("(?:a|b)*a(?:a|b){16}").(*Pattern)
	read.compiled.once.Do(func() { read.compiled.machine = build(read.compiled.meaning) })
	m := read.compiled.machine
	w := m.newWalk()
	rng := uint32(7)
	random := func(n int) string {
		var b strings.Builder
		for range n {
			rng = rng*1664525 + 1013904223
			b.WriteByte("ab"[rng>>31])
		}
		return b.String()
	}
	check := func(subject string) {
		t.Helper()
		want := len(subject) >= 17 && subject[len(subject)-17] == 'a'
		if got := m.matchesIn(w, subject); got != want {
			t.Fatalf("%q... is %v, not %v", subject[:min(20, len(subject))], got, want)
		}
	}
	for !w.known.off {
		check(random(20_000))
	}
	if w.known.wait() != retryBytes {
		t.Fatalf("the first give-up waits %d, not %d", w.known.wait(), retryBytes)
	}
	// Waited for, so that the test reads less than the constant says.
	w.known.offFor = 1000
	check(strings.Repeat("ab", 400))
	if !w.known.off {
		t.Fatal("it tried again before it read what it waits for")
	}
	check(strings.Repeat("ab", 400))
	if w.known.off {
		t.Fatal("it did not try again once it had")
	}
	for !w.known.off {
		check(random(20_000))
	}
	if w.known.wait() != 2000 {
		t.Fatalf("a try that gave up again waits %d, not 2000", w.known.wait())
	}
	w.known.offRead = w.known.wait()
	for range 1000 {
		check(strings.Repeat("ab", 20))
	}
	if w.known.off {
		t.Fatal("subjects whose sets are looked up again made it give up")
	}
}
