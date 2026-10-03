package notation199x

// knownBytes is about how much one walk keeps of the sets it has worked out, before it forgets
// them and starts again. A set that alone takes more is still kept, as the only one: the machine
// bounds what it takes, and a walk that keeps coming back to it is one lookup a character.
var knownBytes = 2 << 20

// knownSet is a set of states a walk has been in, and where the characters it has read from it
// lead: the sets of states a deterministic machine would have, made only as a walk comes to them.
//
// A set leads to another by pointer and not by a place in a list, so a set that is forgotten is
// still the set it was to whatever holds it: nothing can be held that names a different set.
type knownSet struct {
	// states is the states of the set, every step for nothing already taken, in the order the walk
	// put them in, which says nothing about the set.
	states []int32
	// hash is the sum of scatter over states, by which the set is looked up.
	hash    uint32
	accepts bool
	// none is whether the set has no state, so that no string from here on is accepted.
	none bool
	// ascii is where each ASCII character leads, or nil where that is not known yet.
	ascii [utf8RuneSelf]*knownSet
	// other is where each other character leads, where that is known.
	other map[rune]*knownSet
}

const utf8RuneSelf = 0x80

// knownSets is the sets one walk has worked out, held to about knownBytes, and nothing else: what
// is here is what was worked out, and never what a walk decided from how often it was looked up.
// Which sets are kept, and whether any are, changes how fast a walk is and no answer. Whether to
// forget them when they are full, or to go on without keeping more, is the match's to decide
// ([machine.hold]), and is decided again in every match.
//
// A set is looked up in slots by its hash, a sum over its states that is the same in whatever
// order the walk put them in, so the set is never put in order. The hash is summed where a set is
// looked up (hashOf) and not as each state is put in, which a walk without kept sets does too.
// What goes over a set, over the slots or over the sets kept is a loop of its own, among those
// walk lists.
type knownSets struct {
	// slots holds each kept set at its hash or past it, a power of two of them and at least twice
	// as many as are kept, or none before a set is kept.
	slots []*knownSet
	// first is the set a walk starts in, or nil where it is not known.
	first *knownSet
	// kept is how many sets are kept, and bytes about what they take.
	kept, bytes int
}

// forget forgets every set kept. A set held across it is still the set it was, and is no longer
// looked up.
func (k *knownSets) forget() {
	k.slots = nil
	k.first = nil
	k.kept, k.bytes = 0, 0
}

// keep is the set now holds, whose hash is hash: found where it is kept, and otherwise kept, made
// reporting so. It is nil where there is no room for it beside the sets kept. A set is kept where
// none is, whatever it takes.
func (k *knownSets) keep(m *machine, now *stateSet, hash uint32) (set *knownSet, made bool) {
	if set := k.find(now, hash); set != nil {
		return set, false
	}
	// The set's states, where it leads for each ASCII character, and its part of the slots and of
	// what holds it.
	cost := 4*len(now.states()) + 8*utf8RuneSelf + 64
	if k.kept > 0 && k.bytes+cost > knownBytes {
		return nil, false
	}
	if (k.kept+1)*2 > len(k.slots) {
		k.grow()
	}
	states := make([]int32, len(now.states()))
	for i, q := range now.states() {
		states[i] = q
	}
	set = &knownSet{states: states, hash: hash, accepts: now.has(m.accept), none: len(states) == 0}
	k.slots[k.free(set.hash)] = set
	k.bytes += cost
	k.kept++
	return set, true
}

// lead records that r leads from from to to, where there is room for it: a step over ASCII is
// room keep charged from for, and another takes room of its own.
func (k *knownSets) lead(from *knownSet, r rune, to *knownSet) {
	if r < utf8RuneSelf {
		from.ascii[r] = to
	} else if k.bytes < knownBytes {
		if from.other == nil {
			from.other = make(map[rune]*knownSet)
		}
		from.other[r] = to
		k.bytes += 16
	}
}

// hashOf is the sum of scatter over the states now holds. It is summed here and not as each state
// is put in, so a walk that keeps no sets does not sum it.
func hashOf(now *stateSet) uint32 {
	var hash uint32
	for _, q := range now.states() {
		hash += scatter(q)
	}
	return hash
}

// scatter is a state's part of the hash of a set it is in.
func scatter(q int32) uint32 {
	mixed := uint32(q) * 0x9E3779B9
	return mixed ^ mixed>>15
}

// find is the kept set that is the set now holds, whose hash is hash, or nil where it is not kept:
// the slots from hash on are probed until an empty one.
func (k *knownSets) find(now *stateSet, hash uint32) *knownSet {
	if len(k.slots) == 0 {
		return nil
	}
	mask := len(k.slots) - 1
	for at := int(hash) & mask; k.slots[at] != nil; at = (at + 1) & mask {
		if held := k.slots[at]; held.hash == hash && same(held, now) {
			return held
		}
	}
	return nil
}

// same is whether held is the set now holds: as many states, each of which now has, asked one at
// a time. Sets with the same hash are told apart here, so a hash shared by two sets changes no
// answer.
func same(held *knownSet, now *stateSet) bool {
	if len(held.states) != len(now.states()) {
		return false
	}
	for _, q := range held.states {
		if !now.has(q) {
			return false
		}
	}
	return true
}

// free is the first empty slot from hash on.
func (k *knownSets) free(hash uint32) int {
	mask := len(k.slots) - 1
	at := int(hash) & mask
	for k.slots[at] != nil {
		at = (at + 1) & mask
	}
	return at
}

// grow makes twice the slots, or sixteen, and puts each kept set in them again by its hash. What is
// gone over is the kept sets, at most what knownBytes holds.
func (k *knownSets) grow() {
	old := k.slots
	k.slots = make([]*knownSet, max(16, 2*len(old)))
	for _, set := range old {
		if set != nil {
			k.slots[k.free(set.hash)] = set
		}
	}
}
