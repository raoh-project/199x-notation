package notation199x

import (
	"go/ast"
	"go/parser"
	"go/token"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"unsafe"
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

// The walk a match leaves, with the sets it kept, is the one the next match is in, a collection
// between them or not. In a sync.Pool, the match after a collection made a walk anew: on a machine
// of 250,000 states, 5 MB and as long as 2,500 matches that go by kept steps.
func TestTheNextMatchIsInTheWalkTheLastLeftEvenAfterACollection(t *testing.T) {
	m := pathMachine("a{0,1000}")
	m.matches("aaa")
	left := m.spare.Load()
	if left == nil || left.known.kept == 0 {
		t.Fatal("the match left no walk, or one that kept no set")
	}
	runtime.GC()
	runtime.GC()
	m.matches("aaa")
	if w := m.spare.Load(); w != left || w.worked != 0 || w.read != 3 {
		t.Fatal("the match after a collection was not in the walk the last one left, by its steps")
	}
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

// largePath is a machine whose sets are as large as it, and what it is matched against: a match
// comes to a new set at every character, so it soon keeps no more and walks the rest a state at
// a time, looking each set it comes to up among those it kept.
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
		}
	}
	// The table keeps its room, and what it is charged for, with no step in it.
	others := &w.known.others
	clear(others.keys)
	clear(others.to)
	others.count = 0
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
	}
	return known + w.known.others.count
}

func TestEachTimedWalkGoesTheWayItIsNamed(t *testing.T) {
	m, as := largePath()
	frozen := m.newWalk()
	m.matchesIn(frozen, as)
	if !frozen.frozen || frozen.in != nil || frozen.known.kept*10 > len(as) {
		t.Fatalf("a match of the large machine kept %d sets and goes on by them", frozen.known.kept)
	}

	m, subject := tenthPath()
	w := m.newWalk()
	m.matchesIn(w, subject)
	made := w.known.kept
	if made*2 <= len(subject) || w.frozen || w.forgot {
		t.Fatalf("a new set at %d of %d characters", made, len(subject))
	}

	steps := stepsKnown(w)
	m.matchesIn(w, subject)
	if w.worked != 0 || w.read != len(subject) || stepsKnown(w) != steps {
		t.Fatal("a match over steps worked out made a set or worked a step out")
	}

	forgetSteps(w)
	m.matchesIn(w, subject)
	if found := stepsKnown(w); w.known.kept != made || found*2 <= len(subject) || w.frozen {
		t.Fatalf("%d sets kept of %d, and a kept set found at %d of %d characters", w.known.kept, made, found, len(subject))
	}
}

// A match that keeps no more sets: past the few it keeps, every character moves each state, and
// the set they come to is looked for among those kept.
func BenchmarkAMatchThatKeepsNoMoreSets(b *testing.B) {
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
// every few characters, and with the room a walk is given. With no room, a set is kept only where
// it is the one kept, so a match soon keeps no more and walks a state at a time.
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
	first := w.known.keep(m, w.now, hashOf(w.now))
	put(3, 1, 2)
	again := w.known.keep(m, w.now, hashOf(w.now))
	if again != first || w.known.kept != 1 {
		t.Fatalf("{3, 1, 2} was kept apart from {1, 2, 3}: %d sets kept", w.known.kept)
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
	held := w.known.keep(m, w.now, hashOf(w.now))
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
	if read.compiled.machine.size() < 4 {
		t.Fatalf("%q has too few states", pattern)
	}
	return read.compiled.machine
}

// tenthOf17 is the machine whose seventeenth character from the end is an a, and what it answers
// for a subject. Its deterministic machine has more sets than a walk keeps.
func tenthOf17() (*machine, func(string) bool) {
	return pathMachine("(?:a|b)*a(?:a|b){16}"), func(subject string) bool {
		return len(subject) >= 17 && subject[len(subject)-17] == 'a'
	}
}

// A match whose new steps lead only to sets already kept, and fill the room with steps, is frozen
// once it fills the room the second time having read little by kept steps, as a match that fills
// it with sets is: what it worked out is counted step by step, and not set by set. The y before
// the thousand and twenty-four characters cuts them into a class each, and every one of them leads
// the set a walk goes round back to itself. Counted in sets made, the match counted nothing it
// worked out, was never frozen, and started the kept sets again each time it filled the room.
func TestAMatchThatFillsTheRoomWithStepsIsFrozenAsOneThatFillsItWithSets(t *testing.T) {
	var text strings.Builder
	text.WriteString("y(?:")
	for c := rune(0x100); c < 0x500; c++ {
		if c > 0x100 {
			text.WriteByte('|')
		}
		text.WriteRune(c)
	}
	text.WriteString(`)|[\x{100}-\x{4FF}]*`)
	m := machineOf(t, text.String())
	w := m.newWalk()
	if !m.matchesIn(w, "\u0100\u0101") {
		t.Fatal("two of the characters are not accepted")
	}
	defer func(was int) { knownBytes = was }(knownBytes)
	// Room for the sets and a few dozen steps past ASCII.
	knownBytes = w.known.bytes + 2048
	seed := uint32(11)
	var subject strings.Builder
	for range 20_000 {
		seed = seed*1664525 + 1013904223
		subject.WriteRune(rune(0x100 + seed>>22))
	}
	if !m.matchesIn(w, subject.String()) {
		t.Fatal("the characters at random are not accepted")
	}
	if w.known.kept > 3 || !w.forgot || !w.frozen {
		t.Fatalf("%d sets kept, forgot %v, frozen %v, worked %d, read %d", w.known.kept, w.forgot,
			w.frozen, w.worked, w.read)
	}
}

// slotsHold is whether set is among the sets w keeps.
func slotsHold(w *walk, set *knownSet) bool {
	for _, held := range w.known.slots {
		if held == set {
			return true
		}
	}
	return false
}

// isOthers is whether sel is a call on the table of other steps, k.others.
func isOthers(sel *ast.SelectorExpr) bool {
	inner, ok := sel.X.(*ast.SelectorExpr)
	return ok && inner.Sel.Name == "others"
}

// randomAB is n characters of a and b at random, the same for the same seed.
func randomAB(n int, seed uint32) string {
	var b strings.Builder
	for range n {
		seed = seed*1664525 + 1013904223
		b.WriteByte("ab"[seed>>31])
	}
	return b.String()
}

// A match that comes to a new set at nearly every character fills the kept sets, forgets them,
// fills them again and keeps no more; the match after it, of a subject whose sets are few, keeps
// sets again from its first character, and the one after that reads every character by steps
// already worked out. Before, the first match left the walk keeping no sets for the next 32 MiB
// it walked, and a match of 800 bytes after it took 150 times as long as on a new walk.
func TestAMatchThatComesToNewSetsSlowsNoMatchAfterIt(t *testing.T) {
	m, want := tenthOf17()
	w := m.newWalk()
	hostile := randomAB(200_000, 7)
	if got := m.matchesIn(w, hostile); got != want(hostile) {
		t.Fatalf("the random subject is %v", got)
	}
	if !w.forgot || !w.frozen {
		t.Fatalf("a match of new sets forgot %v and kept no more %v", w.forgot, w.frozen)
	}
	// Forgetting the kept sets kept the one a walk starts in, so the next match starts in a kept
	// set and does not forget the others to keep it.
	if first := w.known.first; first == nil || !slotsHold(w, first) {
		t.Fatal("the set a walk starts in was forgotten")
	}
	friendly := strings.Repeat("ab", 400)
	if got := m.matchesIn(w, friendly); got != want(friendly) {
		t.Fatalf("%q... is %v", friendly[:20], got)
	}
	if w.frozen || w.worked == 0 {
		t.Fatalf("the next match kept no more %v, and worked %d steps out", w.frozen, w.worked)
	}
	if got := m.matchesIn(w, friendly); got != want(friendly) || w.worked != 0 || w.read != len(friendly) || w.forgot {
		t.Fatalf("the match after it worked %d steps out and read %d of %d characters by kept steps", w.worked, w.read, len(friendly))
	}
}

// A match that keeps no more sets keeps nothing more, sets or steps between them, however long it
// goes on: what it holds is what it held when it stopped keeping them.
func TestAMatchThatKeepsNoMoreSetsKeepsNothingMore(t *testing.T) {
	m, want := tenthOf17()
	w := m.newWalk()
	hostile := randomAB(200_000, 7)
	m.matchesIn(w, hostile)
	if !w.frozen {
		t.Fatal("the random subject did not make the match keep no more sets")
	}
	kept, bytes, steps := w.known.kept, w.known.bytes, stepsKnown(w)
	for _, r := range randomAB(50_000, 11) {
		m.take(w, r)
	}
	if w.known.kept != kept || w.known.bytes != bytes || stepsKnown(w) != steps {
		t.Fatalf("%d sets, %d bytes and %d steps became %d, %d and %d", kept, bytes, steps,
			w.known.kept, w.known.bytes, stepsKnown(w))
	}
	if got := m.matchesIn(w, hostile); got != want(hostile) {
		t.Fatalf("the random subject is %v again", got)
	}
}

// A match that keeps no more sets goes on by them again wherever a step a state at a time comes to
// one it keeps: from the states of a kept set, walked a state at a time, a step whose set is kept
// leads back to that set.
func TestAMatchThatKeepsNoMoreSetsComesBackToThoseItKeeps(t *testing.T) {
	m, _ := tenthOf17()
	w := m.newWalk()
	m.matchesIn(w, randomAB(200_000, 7))
	if !w.frozen {
		t.Fatal("the random subject did not make the match keep no more sets")
	}
	for _, from := range w.known.slots {
		if from == nil {
			continue
		}
		for c, to := range from.ascii {
			if to == nil {
				continue
			}
			w.in = nil
			w.now.clear()
			for _, q := range from.states {
				w.now.add(q)
			}
			m.take(w, rune(c))
			if w.in != to {
				t.Fatalf("%q from a kept set walked a state at a time did not come back to the set kept", rune(c))
			}
			return
		}
	}
	t.Fatal("no step between kept sets was found")
}

// A set that alone takes more room than the kept sets are given is kept, beside the set a walk
// starts in and no other: a match that keeps coming back to it reads by its steps, and does not
// walk the whole machine at every character, and the next match finds both kept.
func TestASetLargerThanTheRoomIsKeptBesideTheStart(t *testing.T) {
	defer func(was int) { knownBytes = was }(knownBytes)
	knownBytes = 1
	m := pathMachine("(?:x*){500}")
	w := m.newWalk()
	subject := strings.Repeat("x", 1000)
	if !m.matchesIn(w, subject) {
		t.Fatal("x* repeated does not accept x")
	}
	if w.frozen || w.in == nil || w.known.kept != 2 || w.in == w.known.first {
		t.Fatalf("the set was not kept beside the start: %d kept, keeping no more %v", w.known.kept, w.frozen)
	}
	// The two are kept beside the room and take none of it.
	if w.known.bytes > knownBytes || w.known.needed <= knownBytes {
		t.Fatalf("%d bytes charged in a room of %d, and %d beside it", w.known.bytes, knownBytes, w.known.needed)
	}
	// The step from the set a walk starts in to it was kept with it, so the next match reads every
	// character by kept steps, keeping no new set.
	m.matchesIn(w, subject)
	if w.frozen || w.worked != 0 || w.read != len(subject) {
		t.Fatalf("the next match read %d of %d by kept steps", w.read, len(subject))
	}
}

// The sets every walk needs take none of the room, so the steps from a set larger than the room
// are kept in it as any are, past ASCII as over it. Every character from U+0100 to U+03FF leads the
// set the walk goes round back to itself, and it and the set a walk starts in are each larger than
// the room; the 768 steps fit in it. The next match reads every character by kept steps and is not
// frozen. Before, those two sets were charged in the room, so no step past ASCII from them fitted,
// and every match was frozen on its first.
func TestStepsFromASetLargerThanTheRoomAreKeptInTheRoom(t *testing.T) {
	defer func(was int) { knownBytes = was }(knownBytes)
	knownBytes = 64 << 10
	m := pathMachine("(?:[\u0100-\u03FF]*){20000}")
	var subject strings.Builder
	for range 2 {
		for r := rune(0x100); r < 0x400; r++ {
			subject.WriteRune(r)
		}
	}
	text := subject.String()
	w := m.newWalk()
	if !m.matchesIn(w, text) {
		t.Fatal("the pattern does not accept the subject")
	}
	if w.known.kept != 2 || w.known.needed <= knownBytes || w.known.bytes > knownBytes {
		t.Fatalf("%d sets kept, %d bytes beside a room of %d and %d in it", w.known.kept,
			w.known.needed, knownBytes, w.known.bytes)
	}
	m.matchesIn(w, text)
	if w.frozen || w.worked != 0 || w.read != len([]rune(text)) {
		t.Fatalf("the next match read %d of %d characters by kept steps; kept no more %v",
			w.read, len([]rune(text)), w.frozen)
	}
}

// A step that does not fit beside the kept sets is not left out without a word: it goes to the
// match's decision as a set that does not fit does, and the kept sets are forgotten and kept
// again with the step. The room here holds the sets of (?:é|ü)* and little more, so the steps
// over é and ü do not fit. Before, a step past ASCII that did not fit was not kept, and nothing
// was forgotten either, since no set was new: every match after walked each of those steps a
// state at a time, for as long as the walk was kept.
func TestAStepThatDoesNotFitIsDecidedOnAsASetIs(t *testing.T) {
	defer func(was int) { knownBytes = was }(knownBytes)
	m := pathMachine("(?:é|ü)*")
	subject := strings.Repeat("éü", 100)
	knownBytes = 1 << 20
	all := m.newWalk()
	m.matchesIn(all, subject)
	steps := stepsKnown(all)
	// The least room whose first match forgets nothing.
	low, high := 1, 1<<20
	for low < high {
		mid := (low + high) / 2
		knownBytes = mid
		w := m.newWalk()
		m.matchesIn(w, subject)
		if w.forgot {
			low = mid + 1
		} else {
			high = mid
		}
	}
	knownBytes = low
	w := m.newWalk()
	m.matchesIn(w, subject)
	m.matchesIn(w, subject)
	if stepsKnown(w) != steps || w.worked != 0 || w.read != len([]rune(subject)) {
		t.Fatalf("with room for the sets and not their steps, %d of %d steps are kept, and the "+
			"second match worked %d steps out and read %d characters by kept steps", stepsKnown(w), steps,
			w.worked, w.read)
	}
}

// What the kept sets are charged is what they hold, but for the allocator's rounding: keeping sets
// until they fill the room grows the heap by the bytes charged and not much more or less, over
// ASCII, whose steps are kept in each set, and past it, whose steps are kept in the table of other
// steps. Before, a step past ASCII was charged 16 bytes, which says nothing of what a Go map takes.
func TestKeptSetsChargeWhatTheyTake(t *testing.T) {
	if size := int(unsafe.Sizeof(knownSet{})); size > knownSetBytes {
		t.Fatalf("a knownSet takes %d bytes, more than the %d charged", size, knownSetBytes)
	}
	for _, each := range []struct {
		pattern string
		symbols string
	}{
		{"(?:a|b)*a(?:a|b){16}", "ab"},
		{"(?:é|ü)*é(?:é|ü){16}", "éü"},
	} {
		m := pathMachine(each.pattern)
		symbols := []rune(each.symbols)
		var subject strings.Builder
		seed := uint32(7)
		for range 200_000 {
			seed = seed*1664525 + 1013904223
			subject.WriteRune(symbols[seed>>31])
		}
		text := subject.String()
		w := m.newWalk()
		var before, after runtime.MemStats
		runtime.GC()
		runtime.ReadMemStats(&before)
		m.matchesIn(w, text)
		runtime.GC()
		runtime.ReadMemStats(&after)
		// Held across both readings, so that what the heap lets go of is none of them.
		runtime.KeepAlive(w)
		runtime.KeepAlive(text)
		grew := float64(after.HeapAlloc) - float64(before.HeapAlloc)
		charged := float64(w.known.bytes + w.known.needed)
		// The allocator rounds each list up to its size class, an eighth more at most.
		if !w.frozen || grew < 0.9*charged || grew > 1.25*charged {
			t.Errorf("%s: %.0f bytes charged and the heap grew %.0f; kept no more %v", each.pattern,
				charged, grew, w.frozen)
		}
	}
}

// Nothing starts the kept sets again but machine.forgets, which is where a match decides what to
// do when something does not fit, and machine.begin, for a walk that has kept nothing; and nothing
// but knownSets.afresh, which they call, keeps anything beside knownBytes.
func TestOnlyTheMatchsDecisionForgetsTheKeptSets(t *testing.T) {
	fset := token.NewFileSet()
	for _, name := range []string{"pattern_machine.go", "pattern_known.go"} {
		file, err := parser.ParseFile(fset, name, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		for _, decl := range file.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok {
				continue
			}
			by := funcName(fn)
			ast.Inspect(fn.Body, func(n ast.Node) bool {
				call, ok := n.(*ast.CallExpr)
				if !ok {
					return true
				}
				sel, ok := call.Fun.(*ast.SelectorExpr)
				if !ok {
					return true
				}
				switch sel.Sel.Name {
				case "afresh":
					if by != "machine.forgets" && by != "machine.begin" {
						t.Errorf("%s starts the kept sets again", by)
					}
				case "put", "charge", "grow":
					// Whether something is kept beside knownBytes is its last argument: true only
					// in afresh, needed only where put and grow pass on what they were asked, and
					// false everywhere else. otherSteps' put and grow keep nothing of their own.
					if len(call.Args) == 0 || by == "otherSteps.put" || isOthers(sel) {
						return true
					}
					last, _ := call.Args[len(call.Args)-1].(*ast.Ident)
					switch {
					case last != nil && last.Name == "false":
					case last != nil && last.Name == "true" && by == "knownSets.afresh":
					case last != nil && last.Name == "needed" && (by == "knownSets.put" || by == "knownSets.grow"):
					default:
						t.Errorf("%s keeps something beside knownBytes", by)
					}
				}
				return true
			})
		}
	}
}

// A pattern with no anchor is its meaning once it is read, and placing its anchors makes nothing:
// every part was read as what it means. Around an anchor, the parts between those holding one are
// one part, read as what they mean.
func TestAPatternWithNoAnchorIsItsMeaningOnceRead(t *testing.T) {
	for _, text := range []string{strings.Repeat("ab|[c-e]x?", 100) + "(?:f|g)*h{2,3}",
		strings.Repeat("abc", 100) + "(?:d|e)*"} {
		w := (&patternReader{text: text}).pattern()
		if w.kind != meantWritten {
			t.Fatalf("a pattern with no anchor was read as a written of kind %d", w.kind)
		}
		if made := testing.AllocsPerRun(10, func() { placeAnchors(&w) }); made != 0 {
			t.Fatalf("placing no anchor made %v allocations", made)
		}
	}
	// The parts between the anchors are one part, a run of what they mean, and placing the
	// anchors walks three parts however many there are.
	anchored := (&patternReader{text: "^(?:ab|c)*d" + strings.Repeat("e", 1_000) + "$"}).pattern()
	if anchored.kind != inTurnWritten || len(anchored.parts) != 3 || anchored.parts[1].kind != runWritten ||
		len(anchored.parts[1].meaning.parts) != 1_002 {
		t.Fatalf("^(?:ab|c)*de...$ was read as %d parts", len(anchored.parts))
	}
}

// Where the anchors come to nothing, a run left alone is what the sequence means as it was read,
// and putting it together makes nothing for its parts; beside other parts, the sequence is one
// list of as many parts as it comes to.
func TestARunTheAnchorsLeaveIsTakenAsItIs(t *testing.T) {
	var walked [2]float64
	for at, length := range []int{1_000, 100_000} {
		anchored := (&patternReader{text: "^" + strings.Repeat("e", length) + "$"}).pattern()
		if anchored.kind != inTurnWritten || len(anchored.parts) != 3 || anchored.parts[1].kind != runWritten {
			t.Fatalf("^e...$ was read as %d parts", len(anchored.parts))
		}
		if made := placeAnchors(&anchored); made != anchored.parts[1].meaning {
			t.Fatalf("^e...$ means something other than the run it was read as")
		}
		walked[at] = testing.AllocsPerRun(10, func() { placeAnchors(&anchored) })
	}
	// What is left is the walk's own, three parts long however long the run is.
	if walked[0] != walked[1] {
		t.Fatalf("placing the anchors of ^e...$ made %v allocations, and %v for a run a hundred times as long",
			walked[0], walked[1])
	}
	mixed := (&patternReader{text: "(?:^ab|^c)" + strings.Repeat("e", 1_000) + "$"}).pattern()
	made := placeAnchors(&mixed)
	if made.kind != inTurnMeaning || len(made.parts) != 1_001 || cap(made.parts) != 1_001 {
		t.Fatalf("(?:^ab|^c)e...$ means a sequence of %d parts held in %d", len(made.parts), cap(made.parts))
	}
}

// Reading a character makes nothing of its own: it is a pointer in the run of the sequence it is
// in, and an ASCII character means what every one of it means. So reading a
// literal ten times as long makes no more than a few more slices.
func TestReadingALiteralMakesNothingForEachCharacter(t *testing.T) {
	for _, written := range []string{"abcdefghij", `a\|b\x{64}\.\n`} {
		short := testing.AllocsPerRun(5, func() { ReadPattern(strings.Repeat(written, 1_000)) })
		long := testing.AllocsPerRun(5, func() { ReadPattern(strings.Repeat(written, 10_000)) })
		if long > short+10 {
			t.Fatalf("%q a thousand times made %v allocations and ten thousand times %v", written, short, long)
		}
	}
}
