package notation199x

import (
	"math"
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

// The paths a walk goes by, each timed apart by a benchmark below and held to the way it is named
// by TestEachTimedWalkGoesTheWayItIsNamed, so that what a time says is of that way. Work put in a
// step every path takes for the sake of one is paid by the others, so a change is compared on
// each: one time over all would let what one loses be hidden by what another gains.

// pathMachine is the machine of pattern, built.
func pathMachine(pattern string) *machine {
	read := ReadPattern(pattern).(*Pattern)
	read.compiled.once.Do(func() { read.compiled.machine = build(read.compiled.meaning) })
	return read.compiled.machine
}

// largePath is a machine whose sets are as large as it, so a walk's first match gives keeping
// them up and walks every match after it a state at a time, and what it is matched against.
func largePath() (*machine, string) {
	return pathMachine("(?:a?){49998}"), strings.Repeat("a", 100)
}

// tenthPath is a machine whose tenth character from the end is an a, and 400 characters at random,
// the same on every run: a walk that has kept nothing comes to a new set at most of them.
func tenthPath() (*machine, string) {
	var subject strings.Builder
	rng := uint32(9)
	for range 400 {
		rng = rng*1664525 + 1013904223
		subject.WriteByte("ab"[rng>>31])
	}
	return pathMachine("(?:a|b)*a(?:a|b){8}"), subject.String()
}

// forgetSteps forgets where each character leads from each set w keeps, and keeps the sets: the
// next match works each step out again, and finds the set it leads to among those kept by its hash.
func forgetSteps(w *walk) {
	for _, set := range w.known.slots {
		if set != nil {
			set.ascii = [utf8RuneSelf]*knownSet{}
			set.other = nil
		}
	}
}

// stepsKnown is how many steps from the sets w keeps are worked out.
func stepsKnown(w *walk) int {
	known := 0
	for _, set := range w.known.slots {
		if set == nil {
			continue
		}
		for _, next := range set.ascii {
			if next != nil {
				known++
			}
		}
		known += len(set.other)
	}
	return known
}

func TestEachTimedWalkGoesTheWayItIsNamed(t *testing.T) {
	m, as := largePath()
	alone := m.newWalk()
	m.matchesIn(alone, as)
	if !alone.known.off || alone.known.slots != nil || alone.known.offWork >= alone.known.wait() {
		t.Fatal("a walk of the large machine keeps sets, or keeps them again at its next match")
	}

	m, subject := tenthPath()
	w := m.newWalk()
	m.matchesIn(w, subject)
	made := w.known.made
	if made*2 <= len(subject) || w.known.off {
		t.Fatalf("a new set at %d of %d characters", made, len(subject))
	}

	steps := stepsKnown(w)
	m.matchesIn(w, subject)
	if w.known.made != made || stepsKnown(w) != steps {
		t.Fatal("a match over steps worked out made a set or worked a step out")
	}

	forgetSteps(w)
	m.matchesIn(w, subject)
	if found := stepsKnown(w); w.known.made != made || found*2 <= len(subject) || w.known.off {
		t.Fatalf("%d sets made, and a kept set found at %d of %d characters",
			w.known.made-made, found, len(subject))
	}
}

// A walk without kept sets: every character moves each state.
func BenchmarkAWalkWithoutKeptSets(b *testing.B) {
	m, as := largePath()
	w := m.newWalk()
	m.matchesIn(w, as)
	for range b.N {
		m.matchesIn(w, as)
	}
}

// A walk that has kept no sets comes to a new one at most characters, and keeps each: what a
// character costs where its set is not kept yet, besides moving the states.
func BenchmarkAMatchThatKeepsANewSetAtMostCharacters(b *testing.B) {
	m, subject := tenthPath()
	for range b.N {
		m.matchesIn(m.newWalk(), subject)
	}
}

// A walk over steps from kept sets it has worked out: each character is a lookup.
func BenchmarkAWalkOverStepsWorkedOut(b *testing.B) {
	m, subject := tenthPath()
	w := m.newWalk()
	m.matchesIn(w, subject)
	for range b.N {
		m.matchesIn(w, subject)
	}
}

// A walk that works each step out again and finds the set it leads to among those kept by its
// hash. The steps are forgotten before each match and out of its time.
func BenchmarkAWalkThatFindsKeptSetsByTheirHash(b *testing.B) {
	m, subject := tenthPath()
	w := m.newWalk()
	m.matchesIn(w, subject)
	for range b.N {
		b.StopTimer()
		forgetSteps(w)
		b.StartTimer()
		m.matchesIn(w, subject)
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

// A set is the same set in whatever order a walk put its states in, and is kept once.
func TestASetPutInInAnotherOrderIsTheSetKept(t *testing.T) {
	m := machineOf(t, "(?:a|b|c)*d")
	w := m.newWalk()
	put := func(states ...int32) {
		w.now.clear()
		for _, q := range states {
			w.now.add(q)
		}
	}
	put(1, 2, 3)
	first, _ := w.known.keep(m, w)
	put(3, 1, 2)
	again, _ := w.known.keep(m, w)
	if again != first || w.known.made != 1 {
		t.Fatalf("{3, 1, 2} was kept apart from {1, 2, 3}: %d sets kept", w.known.made)
	}
}

// Two sets with the same hash are told apart by their states, so a hash two sets share changes no
// answer.
func TestSetsWithTheSameHashAreToldApart(t *testing.T) {
	m := machineOf(t, "(?:a|b|c)*d")
	w := m.newWalk()
	w.now.clear()
	w.now.add(1)
	w.now.add(3)
	held, _ := w.known.keep(m, w)
	w.now.clear()
	w.now.add(1)
	w.now.add(2)
	if same(held, w.now) {
		t.Fatal("{1, 3} is taken for {1, 2}")
	}
	if found := w.known.find(w.now, held.hash); found != nil {
		t.Fatalf("{1, 2} was found as %v", found.states)
	}
	// {1, 2} kept with the hash of {1, 3}, in the slot past it.
	made := &knownSet{states: []int32{2, 1}, hash: held.hash}
	w.known.slots[w.known.free(held.hash)] = made
	if again := w.known.find(w.now, held.hash); again != made {
		t.Fatal("{1, 2} is not found once kept beside {1, 3} with the same hash")
	}
}

func machineOf(t *testing.T, pattern string) *machine {
	t.Helper()
	read := ReadPattern(pattern).(*Pattern)
	read.compiled.once.Do(func() { read.compiled.machine = build(read.compiled.meaning) })
	if len(read.compiled.machine.states) < 4 {
		t.Fatalf("%q has too few states", pattern)
	}
	return read.compiled.machine
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
	if w.known.wait() != retryWork {
		t.Fatalf("the first give-up waits %d, not %d", w.known.wait(), retryWork)
	}
	// Waited for, so that the test walks less than the constant says. A walk of 800 bytes walks
	// 801, as retryWork counts, and what has been walked is asked before a walk.
	w.known.offFor = 1000
	// What the walk that gave up walked after is counted too; the count starts from nought here.
	w.known.offWork = 0
	check(strings.Repeat("ab", 400))
	check(strings.Repeat("ab", 400))
	if !w.known.off {
		t.Fatal("it tried again before it walked what it waits for")
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
	w.known.offWork = w.known.wait()
	for range 1000 {
		check(strings.Repeat("ab", 20))
	}
	if w.known.off {
		t.Fatal("subjects whose sets are looked up again made it give up")
	}
}

// What a walk without kept sets walks is what counts toward trying again: an empty subject counts
// the set it starts in, so empty subjects alone lead to a try, and a long subject turned away at
// once counts the little that was walked of it, not its length.
func TestWhatCountsTowardTryingAgainIsWhatWasWalked(t *testing.T) {
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
	for !w.known.off {
		m.matchesIn(w, random(20_000))
	}
	w.known.offFor = 100
	w.known.offWork = 0
	for range 100 {
		if !w.known.off {
			t.Fatal("empty subjects led to a try too soon")
		}
		if m.matchesIn(w, "") {
			t.Fatal("the empty subject is accepted")
		}
	}
	m.matchesIn(w, "")
	if w.known.off {
		t.Fatal("empty subjects alone did not lead to a try")
	}
	for !w.known.off {
		m.matchesIn(w, random(20_000))
	}
	before := w.known.offWork
	if m.matchesIn(w, "c"+strings.Repeat("a", 100_000)) {
		t.Fatal("a subject with a c is accepted")
	}
	if counted := w.known.offWork - before; counted > 3 {
		t.Fatalf("a subject turned away at once counted %d", counted)
	}
}

// A count of what is read is held at the most an int holds, so that on a 32-bit platform it does
// not go round to a negative that would make a room give up keeping sets it uses.
func TestCountsThatOnlyGrowAreHeldAtTheMostAnIntHolds(t *testing.T) {
	if got := grown(math.MaxInt-1, 5); got != math.MaxInt {
		t.Fatalf("grown(MaxInt-1, 5) is %d", got)
	}
	if got := grown(2, 3); got != 5 {
		t.Fatalf("grown(2, 3) is %d", got)
	}
	var k knownSets
	k.offFor, k.retrying = math.MaxInt/2+1, true
	k.giveUp()
	if k.offFor != math.MaxInt {
		t.Fatalf("a wait doubled past the most an int holds is %d", k.offFor)
	}
}

// A walk that gives up keeping sets part of the way through counts what it walks after, as one
// that had given up before it does: the wait before trying again bounds every walk without kept
// sets, wherever it began.
func TestAWalkThatGivesUpOnItsWayCountsWhatItWalksAfter(t *testing.T) {
	read := ReadPattern("(?:a|b)*a(?:a|b){16}").(*Pattern)
	read.compiled.once.Do(func() { read.compiled.machine = build(read.compiled.meaning) })
	m := read.compiled.machine
	w := m.newWalk()
	rng := uint32(7)
	for !w.known.off {
		var b strings.Builder
		for range 20_000 {
			rng = rng*1664525 + 1013904223
			b.WriteByte("ab"[rng>>31])
		}
		m.matchesIn(w, b.String())
	}
	if w.known.offWork <= 1000 {
		t.Fatalf("the walk that gave up counted %d of what it walked after", w.known.offWork)
	}
}
