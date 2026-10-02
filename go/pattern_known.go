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
type knownSet struct {
	// states is the states of the set, ascending, every step for nothing already taken.
	states  []int32
	accepts bool
	// ascii is where each ASCII character leads, as the index of the set plus one, and nought
	// where that is not known yet.
	ascii [utf8RuneSelf]int32
	// other is where each other character leads, where that is known.
	other map[rune]int32
}

const utf8RuneSelf = 0x80

// knownSets is the sets one walk has worked out, held to about knownBytes.
//
// Where they would be more, they are forgotten and worked out again as they are come to. Where
// what was worked out since they were last forgotten was looked up again less than once in ten,
// keeping them saves nothing: the walk is told to go on without them, and so is every later walk
// in the same room. A machine whose sets are large, or subjects that keep coming to new ones, are
// walked a state at a time as without them. Either way no character costs more than moving each
// state the machine has.
type knownSets struct {
	sets  []*knownSet
	index map[string]int32
	// first is the set a walk starts in, or -1 where it is not known.
	first int32
	bytes int
	// made and read are the sets made and the characters read since they were last forgotten.
	made, read int
	// forgotten counts the times they were, so that a set worked out across one is not recorded
	// against a set that is gone.
	forgotten int
	// off is whether keeping them was given up on.
	off bool
	// key is the room a set's key is written in to look it up.
	key []byte
}

func (k *knownSets) forget() {
	k.sets = k.sets[:0]
	if k.index == nil {
		k.index = make(map[string]int32)
	}
	clear(k.index)
	k.first = -1
	k.bytes, k.made, k.read = 0, 0, 0
	k.forgotten++
}

// start is the set a walk starts in, and false where the walk is to go on without what is kept,
// in w.now.
func (k *knownSets) start(m *machine, w *walk) (int32, bool) {
	if k.first >= 0 {
		return k.first, true
	}
	w.now.dense = w.now.dense[:0]
	m.enter(w, w.now, 0)
	if k.off {
		return -1, false
	}
	first, kept := k.keep(m, w)
	if kept {
		k.first = first
	}
	return first, kept
}

// after is the set r leads to from set, and false where the walk is to go on without what is
// kept, in w.now.
func (k *knownSets) after(m *machine, w *walk, set int32, r rune) (int32, bool) {
	k.read++
	from := k.sets[set]
	if r < utf8RuneSelf {
		if next := from.ascii[r]; next != 0 {
			return next - 1, true
		}
	} else if next, ok := from.other[r]; ok {
		return next, true
	}
	w.now.dense = w.now.dense[:0]
	for _, q := range from.states {
		w.now.add(q)
	}
	m.advance(w, r)
	forgotten := k.forgotten
	next, kept := k.keep(m, w)
	if !kept || k.forgotten != forgotten {
		// Forgotten while it was being worked out: where it leads is not kept.
		return next, kept
	}
	if r < utf8RuneSelf {
		from.ascii[r] = next + 1
	} else if k.bytes < knownBytes {
		if from.other == nil {
			from.other = make(map[rune]int32)
		}
		from.other[r] = next
		k.bytes += 16
	}
	return next, true
}

// keep is the set w.now holds, kept where it is not already, and false where the walk is to go on
// without what is kept.
func (k *knownSets) keep(m *machine, w *walk) (int32, bool) {
	// Put in order where it is, which the walk does not depend on: a set is the same set in any
	// order. Each state's place in the list is written again, since the set is asked of it.
	slices.Sort(w.now.dense)
	for i, q := range w.now.dense {
		w.now.sparse[q] = int32(i)
	}
	k.key = k.key[:0]
	for _, q := range w.now.dense {
		k.key = binary.LittleEndian.AppendUint32(k.key, uint32(q))
	}
	if at, ok := k.index[string(k.key)]; ok {
		return at, true
	}
	cost := 2*len(k.key) + 4*utf8RuneSelf + 64
	if k.bytes+cost > knownBytes {
		gaveUp := k.read < 10*k.made || cost > knownBytes
		k.forget()
		if gaveUp {
			k.off = true
			return -1, false
		}
	}
	states := slices.Clone(w.now.dense)
	k.sets = append(k.sets, &knownSet{states: states, accepts: slices.Contains(states, m.accept)})
	at := int32(len(k.sets) - 1)
	k.index[string(k.key)] = at
	k.bytes += cost
	k.made++
	return at, true
}
