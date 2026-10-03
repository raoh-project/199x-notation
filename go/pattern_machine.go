package notation199x

import (
	"sync/atomic"
	"unicode/utf8"
)

// machine is the strings a pattern accepts, as states to walk between: an automaton with steps
// that cost a symbol out of a set, and steps that cost nothing. A choice is a step into either arm
// and a repetition a step back to where it started, so the machine has the states its pattern is
// counted at and no more.
//
// What labels a step is a set of symbols and never one, so a step is as cheap for [^a] as for a.
//
// A state is a place in arrays and nothing made: a state's steps are from its place in stepStart to
// the next state's, each over stepOver to stepTo, and its steps for nothing are from its place in
// freeStart to the next state's, each to freeTo. So a machine of 250,000 states is as many places,
// and its steps as many more, however many states have none.
type machine struct {
	stepStart []int32
	stepTo    []int32
	stepOver  []symbols
	freeStart []int32
	freeTo    []int32
	accept    int32
	// spare is a walk no match is in, with the sets it kept, or nil. A match takes it and puts it
	// back, so that a match does not make room the size of the machine each time, nor work out
	// again the sets the match before it kept. It is held as long as the machine is: a collection
	// does not take it, as it would from a sync.Pool. A match that finds no spare, because another
	// goroutine is in it, makes a walk of its own, and only one walk is put back, so no two
	// goroutines are ever in one walk.
	spare atomic.Pointer[walk]
}

// size is how many states the machine has.
func (m *machine) size() int {
	return len(m.stepStart) - 1
}

// laying is a machine's states and steps while they are made, before they are laid out a state at
// a time (laid). Each step is written where it is made, out of whichever state it leaves, as
// numbers in slices that grow; nothing is made for a state but its number.
type laying struct {
	states   int32
	stepFrom []int32
	stepTo   []int32
	stepOver []symbols
	freeFrom []int32
	freeTo   []int32
}

// build is the machine m is run as. It refuses nothing: m was read within [MachineStates], and
// building makes no more states than the count.
func build(m *meaning) *machine {
	// Made as large as building m makes them, so that nothing is made twice as it grows.
	size := laidSize{states: 1}.plus(sizeOf(m))
	b := &laying{
		stepFrom: make([]int32, 0, size.steps),
		stepTo:   make([]int32, 0, size.steps),
		stepOver: make([]symbols, 0, size.steps),
		freeFrom: make([]int32, 0, size.frees),
		freeTo:   make([]int32, 0, size.frees),
	}
	start := b.state()
	accept := b.build(m, start)
	return b.laid(accept)
}

// laid is the machine b made, its steps laid out a state at a time, a state's in the order they
// were made, whatever order the states were made in.
func (b *laying) laid(accept int32) *machine {
	states := int(b.states)
	m := &machine{
		stepStart: make([]int32, states+1),
		stepTo:    make([]int32, len(b.stepTo)),
		stepOver:  make([]symbols, len(b.stepOver)),
		freeStart: make([]int32, states+1),
		freeTo:    make([]int32, len(b.freeTo)),
		accept:    accept,
	}
	for _, from := range b.stepFrom {
		m.stepStart[from+1]++
	}
	for q := range states {
		m.stepStart[q+1] += m.stepStart[q]
	}
	filled := make([]int32, states)
	copy(filled, m.stepStart)
	for i, from := range b.stepFrom {
		at := filled[from]
		filled[from]++
		m.stepTo[at] = b.stepTo[i]
		m.stepOver[at] = b.stepOver[i]
	}
	for _, from := range b.freeFrom {
		m.freeStart[from+1]++
	}
	for q := range states {
		m.freeStart[q+1] += m.freeStart[q]
	}
	copy(filled, m.freeStart)
	for i, from := range b.freeFrom {
		m.freeTo[filled[from]] = b.freeTo[i]
		filled[from]++
	}
	return m
}

func (b *laying) state() int32 {
	b.states++
	return b.states - 1
}

// build makes the states for m, walked into from from, and answers where it leaves off: one entry
// and one exit apiece, which is what lets the shapes compose without any of them knowing what it
// is inside. Recursive, since a pattern that was read nests no deeper than the nesting depth.
func (b *laying) build(m *meaning, from int32) int32 {
	switch m.kind {
	case nothingMeaning:
		return from
	case neverMeaning:
		// Nothing leads out of it, so nothing after it is reached.
		return b.state()
	case symbolsMeaning:
		to := b.state()
		b.stepFrom = append(b.stepFrom, from)
		b.stepTo = append(b.stepTo, to)
		b.stepOver = append(b.stepOver, m.held)
		return to
	case inTurnMeaning:
		at := from
		for _, part := range m.parts {
			at = b.build(part, at)
		}
		return at
	case eitherOfMeaning:
		out := b.state()
		for _, arm := range m.parts {
			in := b.state()
			b.freely(from, in)
			b.freely(b.build(arm, in), out)
		}
		return out
	case repeatedMeaning:
		return b.repeated(m, from)
	default:
		unreachable("meaning", uint8(m.kind))
		return 0
	}
}

func (b *laying) freely(from, to int32) {
	b.freeFrom = append(b.freeFrom, from)
	b.freeTo = append(b.freeTo, to)
}

// repeated makes a repetition as the copies it is: the floor is copies one after another, what
// is above it is copies each of which may be stepped over, and an unbounded ceiling is one more
// copy with a step back to where it began.
func (b *laying) repeated(m *meaning, from int32) int32 {
	what := m.parts[0]
	// A body that makes no state is the empty string however many times it is taken, and is built
	// as that: one state to end in. Copied a count at a time it would cost the count and make
	// nothing.
	if buildsNoState(what) {
		out := b.state()
		b.freely(from, out)
		return out
	}
	at := from
	for range m.least {
		at = b.build(what, at)
	}
	if m.most == noCeiling {
		loop := b.state()
		b.freely(at, loop)
		b.freely(b.build(what, loop), loop)
		return loop
	}
	out := b.state()
	b.freely(at, out)
	for range m.most - m.least {
		at = b.build(what, at)
		b.freely(at, out)
	}
	return out
}

// laidSize is how many states, steps and steps for nothing building a meaning makes.
type laidSize struct {
	states, steps, frees int
}

func (a laidSize) plus(b laidSize) laidSize {
	return laidSize{a.states + b.states, a.steps + b.steps, a.frees + b.frees}
}

func (a laidSize) times(n int) laidSize {
	return laidSize{a.states * n, a.steps * n, a.frees * n}
}

// sizeOf is what laying.build makes for m, counted shape by shape as it builds them: a repetition
// is its body counted once and multiplied, so this looks at what is written and not at the copies.
// A step is made with the state it leads to, and at most two steps for nothing with each state,
// so no count is more than twice the states the meaning was read within.
func sizeOf(m *meaning) laidSize {
	switch m.kind {
	case nothingMeaning:
		return laidSize{}
	case neverMeaning:
		return laidSize{states: 1}
	case symbolsMeaning:
		return laidSize{states: 1, steps: 1}
	case inTurnMeaning:
		var sum laidSize
		for _, part := range m.parts {
			sum = sum.plus(sizeOf(part))
		}
		return sum
	case eitherOfMeaning:
		sum := laidSize{states: 1}
		for _, arm := range m.parts {
			sum = sum.plus(laidSize{states: 1, frees: 2}).plus(sizeOf(arm))
		}
		return sum
	case repeatedMeaning:
		what := m.parts[0]
		if buildsNoState(what) {
			return laidSize{states: 1, frees: 1}
		}
		body := sizeOf(what)
		if m.most == noCeiling {
			return body.times(m.least + 1).plus(laidSize{states: 1, frees: 2})
		}
		return body.times(m.least).plus(laidSize{states: 1, frees: 1}).
			plus(body.plus(laidSize{frees: 1}).times(m.most - m.least))
	default:
		unreachable("meaning", uint8(m.kind))
		return laidSize{}
	}
}

// buildsNoState is whether building m makes no state, which is only ever the empty string.
func buildsNoState(m *meaning) bool {
	switch m.kind {
	case nothingMeaning:
		return true
	case inTurnMeaning:
		for _, part := range m.parts {
			if !buildsNoState(part) {
				return false
			}
		}
		return true
	case neverMeaning, symbolsMeaning, eitherOfMeaning, repeatedMeaning:
		return false
	default:
		unreachable("meaning", uint8(m.kind))
		return false
	}
}

// stateSet is the states a walk is in: a sparse set, the states in it listed in dense and each
// one's place in that list in sparse. Emptying it is forgetting the list, so a walk over a machine
// of many states that is in few of them costs the few.
//
// Each state's place is held in sparse for as long as it is in the set, which is what has asks
// of. So nothing outside these methods writes to either list.
//
// Every walk puts states in through add, with kept sets or without, so add does only what both
// need. What only keeping a set needs, such as its hash, is worked out where a set is kept.
type stateSet struct {
	dense  []int32
	sparse []int32
}

func newStateSet(size int) *stateSet {
	return &stateSet{dense: make([]int32, 0, size), sparse: make([]int32, size)}
}

func (s *stateSet) has(q int32) bool {
	i := s.sparse[q]
	return int(i) < len(s.dense) && s.dense[i] == q
}

// add puts q in the set, which does not hold it.
func (s *stateSet) add(q int32) {
	s.sparse[q] = int32(len(s.dense))
	s.dense = append(s.dense, q)
}

func (s *stateSet) clear() { s.dense = s.dense[:0] }

// states is the states of the set, in the order they were put in it, which says nothing about the
// set. The slice is the set's own and is read only.
func (s *stateSet) states() []int32 { return s.dense }

// walk is the room one match works in: the sets of states it moves between, the sets it has
// already worked out where a character leads from, and which of the two it is going by.
//
// The kept sets outlast a match, and nothing else here does: what a match decides from how often
// it looked them up (forgot, frozen, made and read) starts again with the next match, in begin.
// One match that keeps coming to new sets therefore slows no match after it.
//
// What a walk does that grows with the subject, the machine or the sets kept is done in these
// loops and in no library call, so a checkpoint added to a match later asks in each of them:
// [machine.matchesIn] over the subject; [machine.advance] over the states and their steps, and
// [machine.enter] over the steps for nothing; [hashOf], [same] and [knownSets.made] over a set;
// [knownSets.find] and [knownSets.free] over the slots, and [knownSets.grow] over the sets kept;
// [otherSteps.get] and [otherSteps.put] over the slots of the table of other steps, and
// [otherSteps.grow] over the steps kept.
// TestTheWalkNamesEveryLoopItHas holds this list to the functions with a loop in them.
type walk struct {
	now, next *stateSet
	pending   []int32
	known     knownSets
	// in is the kept set the walk is in, or nil where it is going on without kept sets and is in
	// now.
	in *knownSet
	// forgot is whether this match has forgotten the kept sets to make room, and frozen whether it
	// keeps no more of them: it goes on by those kept, and a state at a time where they do not
	// lead, until it comes back to one.
	forgot, frozen bool
	// worked and read are the characters this match worked out a state at a time to keep, whether
	// that came to a new set or to a kept one by a new step, and the characters it read by kept
	// steps, a step already known and no other, since it last forgot the kept sets or since it
	// began.
	worked, read int
}

// matches is whether the whole of subject is accepted: every state the machine may be in is
// walked at once, a scalar value at a time, and nothing is gone back over. Bytes that are not
// UTF-8 are no text, and no step is over them.
//
// An ASCII character whose step from the kept set the walk is in is known is taken here, as one
// lookup. Every other character is taken by take.
func (m *machine) matches(subject string) bool {
	w := m.spare.Swap(nil)
	if w == nil {
		w = m.newWalk()
	}
	accepted := m.matchesIn(w, subject)
	m.spare.CompareAndSwap(nil, w)
	return accepted
}

// newWalk is a walk of m that has kept no sets.
func (m *machine) newWalk() *walk {
	// pending holds a state at most once at a time, so it never grows past the machine's states
	// in an append.
	return &walk{now: newStateSet(m.size()), next: newStateSet(m.size()),
		pending: make([]int32, 0, m.size())}
}

// matchesIn is whether the whole of subject is accepted, walked in w, which keeps the sets it
// works out for the next walk in it.
func (m *machine) matchesIn(w *walk, subject string) bool {
	m.begin(w)
	for at := 0; at < len(subject); {
		if c := subject[at]; c < utf8.RuneSelf && w.in != nil {
			if next := w.in.ascii[c]; next != nil {
				w.in = next
				w.read++
				if next.none {
					return false
				}
				at++
				continue
			}
		}
		r, size := utf8.DecodeRuneInString(subject[at:])
		if r == utf8.RuneError && size == 1 {
			return false
		}
		at += size
		if !m.take(w, r) {
			return false
		}
	}
	if w.in != nil {
		return w.in.accepts
	}
	return w.now.has(m.accept)
}

// begin starts a match: it keeps sets again, whatever the match before it decided, and puts the
// walk in the state it starts in, with every state the steps for nothing reach from it, as the
// kept set it starts in.
func (m *machine) begin(w *walk) {
	w.forgot, w.frozen = false, false
	w.worked, w.read = 0, 0
	if w.in = w.known.first; w.in != nil {
		return
	}
	// Only a walk that has kept nothing comes here: starting the kept sets again keeps this one.
	w.now.clear()
	m.enter(w, w.now, 0)
	w.in = w.known.afresh(m, w.now, hashOf(w.now), nil, 0)
}

// take moves the walk over one symbol, and is false where it is in no state after it. Where the
// set it is in is kept and where r leads from it is known, that is where it goes; otherwise its
// states are moved by advance and the set they come to is held, with the step to it
// (machine.hold). Every step a walk takes that does work growing with the machine is taken here.
func (m *machine) take(w *walk, r rune) bool {
	from := w.in
	if from != nil {
		if next := w.known.step(from, r); next != nil {
			w.read++
			w.in = next
			return !next.none
		}
		m.advance(w, from.states, r)
		if !w.frozen {
			w.worked++
		}
	} else {
		m.advance(w, w.now.states(), r)
	}
	next := m.hold(w, from, r)
	if w.in = next; next != nil {
		return !next.none
	}
	return len(w.now.states()) > 0
}

// hold is the kept set the walk has come to over r, in w.now, from the kept set from or from no
// kept set where from is nil: found where it is kept, and otherwise kept now, with the step from
// from to it. It is nil where the match keeps no more sets and this one is not kept, and the walk
// goes on from w.now a state at a time.
//
// This is the one place a walk keeps anything, and so the one place it learns that something does
// not fit: a set or a step alike goes to forgets, which decides what to do.
func (m *machine) hold(w *walk, from *knownSet, r rune) *knownSet {
	hash := hashOf(w.now)
	if w.frozen {
		return w.known.find(w.now, hash)
	}
	set := w.known.keep(m, w.now, hash)
	if set == nil {
		return m.forgets(w, hash, from, r)
	}
	if from == nil || w.known.lead(from, r, set) {
		return set
	}
	if again := m.forgets(w, hash, from, r); again != nil {
		return again
	}
	// The step does not fit and the match keeps no more: it goes on from the set it is in, which
	// is kept, with no step to it.
	return set
}

// forgets decides, for this match only, what is done when a set or a step does not fit beside
// those kept, the set in w.now, whose hash is hash, come to over r from from: it is that set, kept
// beside the set a walk starts in once the others are forgotten (knownSets.afresh), or nil where
// they are not and the match is frozen. Nothing else forgets them, and nothing else freezes a
// match.
//
// The first time, the kept sets are forgotten: they may be another match's, and say nothing of
// this one. After that, they are forgotten again where what this match read by kept steps since
// it last forgot them is ten times what it worked out a state at a time to keep, whether that came
// to a new set or to a kept one by a new step; otherwise keeping them saves nothing, and the
// match keeps no more but goes on by those it has, coming back to them wherever a step a state at
// a time leads to one. So a match that keeps coming to new sets pays for keeping two rooms of them
// and then walks a state at a time, and one whose sets are looked up again pays for keeping them
// out of what it saves.
func (m *machine) forgets(w *walk, hash uint32, from *knownSet, r rune) *knownSet {
	if w.forgot && w.read < 10*w.worked {
		w.frozen = true
		return nil
	}
	w.forgot = true
	w.worked, w.read = 0, 0
	return w.known.afresh(m, w.now, hash, from, r)
}

// advance puts the walk, in w.now, where from leads over one symbol: from each state, each step
// over r, and the states the steps for nothing reach from where those lead. from is w.now's states
// or a kept set's, which is moved from as it is and not put back in w.now first. It is the one
// place states are moved, and its work is the steps out of from and the states it comes to, at
// most the machine's.
func (m *machine) advance(w *walk, from []int32, r rune) {
	w.next.clear()
	for _, q := range from {
		for at := m.stepStart[q]; at < m.stepStart[q+1]; at++ {
			if m.stepOver[at].has(r) {
				m.enter(w, w.next, m.stepTo[at])
			}
		}
	}
	w.now, w.next = w.next, w.now
}

// enter puts q in into, with every state the steps for nothing reach from it, each once.
func (m *machine) enter(w *walk, into *stateSet, q int32) {
	if into.has(q) {
		return
	}
	into.add(q)
	w.pending = append(w.pending[:0], q)
	for len(w.pending) > 0 {
		from := w.pending[len(w.pending)-1]
		w.pending = w.pending[:len(w.pending)-1]
		for _, to := range m.freeTo[m.freeStart[from]:m.freeStart[from+1]] {
			if !into.has(to) {
				into.add(to)
				w.pending = append(w.pending, to)
			}
		}
	}
}
