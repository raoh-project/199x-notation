package notation199x

import (
	"encoding/binary"
	"math"
	"slices"
)

// knownBytes is about how much one walk keeps of the sets it has worked out, before it forgets
// them and starts again.
var knownBytes = 2 << 20

// retryWork is how much walking without kept sets is done after keeping them is given up on,
// before keeping them is tried again: counted as one for each walk, for the set it starts in, and
// one for each byte of the subject walked. Each time a try ends in giving up again the wait
// doubles, so what the tries cost stays a part of that walking that gets smaller.
//
// It is what was walked and not what was handed in that is counted. An empty subject is walked
// too, from the set a walk starts in, and so many of them lead to a try as surely as long ones do;
// a long subject turned away at its first character counts that character, not its length.
var retryWork = 16 * (2 << 20)

// grown is a + b for counts that only grow, held at the most an int holds rather than going
// round, which on a 32-bit platform a count of what is read across walks would reach.
func grown(a, b int) int {
	if a > math.MaxInt-b {
		return math.MaxInt
	}
	return a + b
}

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
// keeping them saves nothing: the walk goes on without them, and so do the walks in the same room
// after it, until they have walked retryWork. Then keeping sets is tried again, as by a
// room that has kept none: a room is kept between matches, in the machine's pool, and what was
// looked up too seldom then says nothing about the subjects read later. A try that gives up again
// waits twice as long before the next, and one that keeps sets long enough to forget them waits
// again as long as the first. A machine whose sets are large, or subjects that keep coming to new
// ones, are walked a state at a time as without them, but for the tries.
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
	// off is whether keeping them was given up on; offWork is what has been walked since, counted
	// as retryWork says, and offFor how much is walked before it is tried again, retryWork where it
	// is zero. retrying is whether it is being tried again, so that giving up waits longer.
	off             bool
	offWork, offFor int
	retrying        bool
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
// While keeping them is given up on, it tries again once what has been walked since is offFor.
func (k *knownSets) start(m *machine, w *walk) *knownSet {
	if k.off {
		if k.offWork < k.wait() {
			return nil
		}
		k.off, k.retrying = false, true
	}
	k.first, _ = k.keep(m, w)
	return k.first
}

// after is the kept set r leads to from from, worked out where that is not known, or nil where
// the walk is to go on without kept sets, in w.now.
func (k *knownSets) after(m *machine, w *walk, from *knownSet, r rune) *knownSet {
	k.read = grown(k.read, 1)
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
			k.giveUp()
			return nil, true
		}
		// The sets were looked up often enough to be worth keeping, so a later give-up waits as
		// long as the first.
		k.offFor, k.retrying = 0, false
		forgot = true
	}
	states := slices.Clone(w.now.states())
	set = &knownSet{states: states, accepts: slices.Contains(states, m.accept), none: len(states) == 0}
	k.index[string(k.key)] = set
	k.bytes += cost
	k.made++
	return set, forgot
}

// wait is how much is walked without kept sets before keeping them is tried again.
func (k *knownSets) wait() int {
	if k.offFor == 0 {
		return retryWork
	}
	return k.offFor
}

// giveUp walks the walks after this without kept sets, until wait() is walked.
func (k *knownSets) giveUp() {
	if k.retrying {
		k.offFor = grown(k.wait(), k.wait())
	}
	k.retrying = false
	k.off = true
	k.offWork = 0
}

// walkedAlone counts a walk without kept sets, which walked bytes bytes of its subject.
func (k *knownSets) walkedAlone(bytes int) {
	k.offWork = grown(grown(k.offWork, bytes), 1)
}
