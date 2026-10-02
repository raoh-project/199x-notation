package notation199x

import (
	"sync"
	"unicode/utf8"
)

// machine is the strings a pattern accepts, as states to walk between: an automaton with steps
// that cost a symbol out of a set, and steps that cost nothing. A choice is a step into either arm
// and a repetition a step back to where it started, so the machine has the states its pattern is
// counted at and no more.
//
// What labels a step is a set of symbols and never one, so a step is as cheap for [^a] as for a.
type machine struct {
	states []state
	accept int32
	// scratch holds what a walk works in, so that a match does not make room the size of the
	// machine each time.
	scratch sync.Pool
}

type state struct {
	steps []step
	free  []int32
}

type step struct {
	over symbols
	to   int32
}

// build is the machine m is run as. It refuses nothing: m was read within [MachineStates], and
// building makes no more states than the count.
func build(m *meaning) *machine {
	b := &machine{}
	start := b.state()
	b.accept = b.build(m, start)
	return b
}

func (b *machine) state() int32 {
	b.states = append(b.states, state{})
	return int32(len(b.states) - 1)
}

// build makes the states for m, walked into from from, and answers where it leaves off: one entry
// and one exit apiece, which is what lets the shapes compose without any of them knowing what it
// is inside. Recursive, since a pattern that was read nests no deeper than the nesting depth.
func (b *machine) build(m *meaning, from int32) int32 {
	switch m.kind {
	case nothingMeaning:
		return from
	case neverMeaning:
		// Nothing leads out of it, so nothing after it is reached.
		return b.state()
	case symbolsMeaning:
		to := b.state()
		b.states[from].steps = append(b.states[from].steps, step{over: m.held, to: to})
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

func (b *machine) freely(from, to int32) {
	b.states[from].free = append(b.states[from].free, to)
}

// repeated makes a repetition as the copies it is: the floor is copies one after another, what
// is above it is copies each of which may be stepped over, and an unbounded ceiling is one more
// copy with a step back to where it began.
func (b *machine) repeated(m *meaning, from int32) int32 {
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
// of. So nothing outside these methods writes to either list. As each state is put in, the set's
// hash and whether it accepts are kept with it, so neither is worked out again by going over the
// set.
type stateSet struct {
	dense  []int32
	sparse []int32
	// accept is the state a walk may stop at.
	accept int32
	// hash is the sum of scatter over the states in the set, the same in whatever order they were
	// put in.
	hash uint32
	// accepting is whether accept is in the set.
	accepting bool
}

func newStateSet(size int, accept int32) *stateSet {
	return &stateSet{dense: make([]int32, 0, size), sparse: make([]int32, size), accept: accept}
}

func (s *stateSet) has(q int32) bool {
	i := s.sparse[q]
	return int(i) < len(s.dense) && s.dense[i] == q
}

// add puts q in the set, which does not hold it.
func (s *stateSet) add(q int32) {
	s.sparse[q] = int32(len(s.dense))
	s.dense = append(s.dense, q)
	s.hash += scatter(q)
	s.accepting = s.accepting || q == s.accept
}

func (s *stateSet) clear() {
	s.dense = s.dense[:0]
	s.hash = 0
	s.accepting = false
}

// states is the states of the set, in the order they were put in it, which says nothing about the
// set. The slice is the set's own and is read only.
func (s *stateSet) states() []int32 { return s.dense }

// scatter is a state's part of the hash of a set it is in.
func scatter(q int32) uint32 {
	mixed := uint32(q) * 0x9E3779B9
	return mixed ^ mixed>>15
}

// walk is the room one match works in: the sets of states it moves between, the sets it has
// already worked out where a character leads from, and which of the two it is going by.
type walk struct {
	now, next *stateSet
	pending   []int32
	known     knownSets
	// in is the kept set the walk is in, or nil where it is going on without kept sets and is in
	// now.
	in *knownSet
}

// matches is whether the whole of subject is accepted: every state the machine may be in is
// walked at once, a scalar value at a time, and nothing is gone back over. Bytes that are not
// UTF-8 are no text, and no step is over them.
//
// An ASCII character whose step from the kept set the walk is in is known is taken here, as one
// lookup. Every other character is taken by take.
func (m *machine) matches(subject string) bool {
	w, _ := m.scratch.Get().(*walk)
	if w == nil {
		w = m.newWalk()
	}
	defer m.scratch.Put(w)
	return m.matchesIn(w, subject)
}

// newWalk is a walk of m that has kept no sets.
func (m *machine) newWalk() *walk {
	w := &walk{now: newStateSet(len(m.states), m.accept), next: newStateSet(len(m.states), m.accept)}
	w.known.forget()
	return w
}

// matchesIn is whether the whole of subject is accepted, walked in w, which keeps what it works
// out for the next walk in it.
func (m *machine) matchesIn(w *walk, subject string) bool {
	m.begin(w)
	for at := 0; at < len(subject); {
		if c := subject[at]; c < utf8.RuneSelf && w.in != nil {
			if next := w.in.ascii[c]; next != nil {
				w.in = next
				w.known.read = grown(w.known.read, 1)
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
	return w.now.accepting
}

// begin puts the walk in the state it starts in, with every state the steps for nothing reach
// from it: the kept set it starts in where that is kept, and otherwise those states, worked out.
func (m *machine) begin(w *walk) {
	if w.in = w.known.first; w.in != nil {
		return
	}
	w.now.clear()
	m.enter(w, w.now, 0)
	w.in = w.known.start(m, w)
}

// take moves the walk over one symbol, and is false where it is in no state after it. Where the
// set it is in is kept, where r leads from it is looked up or worked out and kept (knownSets.after);
// otherwise the set is moved by advance. Every step a walk takes that does work growing with the
// machine is taken here.
func (m *machine) take(w *walk, r rune) bool {
	if w.in != nil {
		if w.in = w.known.after(m, w, w.in, r); w.in != nil {
			return !w.in.none
		}
		return len(w.now.states()) > 0
	}
	m.advance(w, r)
	// The one place a walk without kept sets steps, so what such walks walk is counted here,
	// whether keeping sets was given up on before the walk or during it.
	w.known.walkedAlone(utf8.RuneLen(r))
	return len(w.now.states()) > 0
}

// advance moves the set the walk is in, w.now, over one symbol: from each state, each step over r,
// and the states the steps for nothing reach from where those lead. It is the one place states
// are moved, and its work is the steps out of the set and the states it comes to, at most the
// machine's. Keeping a set it has not kept before is work of the same size besides, in the loops
// knownSets names, so a checkpoint added to a match later asks in those and here.
func (m *machine) advance(w *walk, r rune) {
	w.next.clear()
	for _, q := range w.now.states() {
		for _, s := range m.states[q].steps {
			if s.over.has(r) {
				m.enter(w, w.next, s.to)
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
		for _, to := range m.states[from].free {
			if !into.has(to) {
				into.add(to)
				w.pending = append(w.pending, to)
			}
		}
	}
}
