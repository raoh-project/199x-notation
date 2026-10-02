package notation199x

import (
	"encoding/binary"
	"slices"
)

// knownBytes is about how much one walk keeps of the sets it has worked out, before it forgets
// them and starts again.
var knownBytes = 2 << 20

// knownSet is a set of states a walk has been in, and where the characters it has read from it
// lead: the sets of states a deterministic machine would have, made only as a walk comes to them.
//
// A set leads to another by pointer and not by a place in a list, so a set that is forgotten is
// still the set it was to whatever holds it: nothing can be held that names a different set.
type knownSet struct {
	// states is the states of the set, ascending, every step for nothing already taken.
	states  []int32
	accepts bool
	// none is whether the set has no state, so that no string from here on is accepted.
	none bool
	// ascii is where each ASCII character leads, or nil where that is not known yet.
	ascii [utf8RuneSelf]*knownSet
	// other is where each other character leads, where that is known.
	other map[rune]*knownSet
}

const utf8RuneSelf = 0x80

// knownSets is the sets one walk has worked out, held to about knownBytes. Which sets are kept,
// and whether any are, changes how fast a walk is and no answer.
//
// Where they would be more, they are forgotten and worked out again as they are come to. Where
// what was worked out since they were last forgotten was looked up again less than once in ten,
// keeping them saves nothing: the walk goes on without them, and so does every later walk in the
// same room. A machine whose sets are large, or subjects that keep coming to new ones, are walked
// a state at a time as without them.
//
// Keeping a set goes over it: it is put in order, its key is written and it is copied, which for
// a set of k states is k log k. So a character that comes to a set not kept costs that besides
// moving the states (advance), and a checkpoint added to a match later asks in these loops too.
type knownSets struct {
	index map[string]*knownSet
	// first is the set a walk starts in, or nil where it is not known.
	first *knownSet
	bytes int
	// made and read are the sets made and the characters read since they were last forgotten.
	made, read int
	// off is whether keeping them was given up on.
	off bool
	// key is the room a set's key is written in to look it up.
	key []byte
}

// forget forgets every set kept. A set held across it is still the set it was, and is no longer
// looked up.
func (k *knownSets) forget() {
	if k.index == nil {
		k.index = make(map[string]*knownSet)
	}
	clear(k.index)
	k.first = nil
	k.bytes, k.made, k.read = 0, 0, 0
}

// start keeps the set a walk starts in, which w.now holds, or is nil where sets are not kept.
func (k *knownSets) start(m *machine, w *walk) *knownSet {
	if k.off {
		return nil
	}
	k.first, _ = k.keep(m, w)
	return k.first
}

// after is the kept set r leads to from from, worked out where that is not known, or nil where
// the walk is to go on without kept sets, in w.now.
func (k *knownSets) after(m *machine, w *walk, from *knownSet, r rune) *knownSet {
	k.read++
	if r < utf8RuneSelf {
		if next := from.ascii[r]; next != nil {
			return next
		}
	} else if next, ok := from.other[r]; ok {
		return next
	}
	w.now.clear()
	for _, q := range from.states {
		w.now.add(q)
	}
	m.advance(w, r)
	next, forgot := k.keep(m, w)
	if next == nil || forgot {
		// from was forgotten to make room, and is not looked up again.
		return next
	}
	if r < utf8RuneSelf {
		from.ascii[r] = next
	} else if k.bytes < knownBytes {
		if from.other == nil {
			from.other = make(map[rune]*knownSet)
		}
		from.other[r] = next
		k.bytes += 16
	}
	return next
}

// keep is the set w.now holds, kept where it is not already, or nil where keeping sets is given
// up on. forgot is whether the sets kept before were forgotten to make room for it.
func (k *knownSets) keep(m *machine, w *walk) (set *knownSet, forgot bool) {
	// A set is the same set in any order, and its key is written of its states in order.
	w.now.sort()
	k.key = k.key[:0]
	for _, q := range w.now.states() {
		k.key = binary.LittleEndian.AppendUint32(k.key, uint32(q))
	}
	if set, ok := k.index[string(k.key)]; ok {
		return set, false
	}
	cost := 2*len(k.key) + 8*utf8RuneSelf + 64
	if k.bytes+cost > knownBytes {
		gaveUp := k.read < 10*k.made || cost > knownBytes
		k.forget()
		if gaveUp {
			k.off = true
			return nil, true
		}
		forgot = true
	}
	states := slices.Clone(w.now.states())
	set = &knownSet{states: states, accepts: slices.Contains(states, m.accept), none: len(states) == 0}
	k.index[string(k.key)] = set
	k.bytes += cost
	k.made++
	return set, forgot
}
