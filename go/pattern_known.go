package notation199x

import "hash/maphash"

// knownBytes is about how much one walk keeps of the sets it has worked out, before it forgets
// them and starts again. Past it are kept only the set a walk starts in and the set the walk is
// in, with the step between them, which are kept whatever they take: the machine bounds what they
// take, and a walk that keeps coming back to a set larger than the room reads it by kept steps.
var knownBytes = 2 << 20

// knownSet is a set of states a walk has been in, and where the ASCII characters it has read from
// it lead: the sets of states a deterministic machine would have, made only as a walk comes to
// them. Where the other characters lead is kept for all sets in one table, knownSets.others.
//
// A set leads to another by pointer and not by a place in a list, so a set that is forgotten is
// still the set it was to whatever holds it: nothing can be held that names a different set.
type knownSet struct {
	// states is the states of the set, every step for nothing already taken, in the order the walk
	// put them in, which says nothing about the set.
	states []int32
	// hash is the sum of scatter over states, by which the set is looked up.
	hash uint32
	// id names the set in the table of steps over other characters: from 1, in the order the sets
	// were kept since they were last forgotten.
	id      uint32
	accepts bool
	// none is whether the set has no state, so that no string from here on is accepted.
	none bool
	// ascii is where each ASCII character leads, or nil where that is not known yet.
	ascii [utf8RuneSelf]*knownSet
}

const utf8RuneSelf = 0x80

// knownSetBytes is what one knownSet takes besides its states: its ASCII steps and the rest of the
// struct. TestKeptSetsChargeWhatTheyTake holds it to the struct's size.
const knownSetBytes = 8*utf8RuneSelf + 64

// knownSets is the sets one walk has worked out, and nothing else: what is here is what was worked
// out, and never what a walk decided from how often it was looked up. Which sets are kept, and
// whether any are, changes how fast a walk is and no answer.
//
// What it promises is what it keeps and what that takes. Each method that keeps something, a set
// (keep) or a step (lead), keeps it where it fits in knownBytes, and otherwise keeps nothing and
// says so: none of them forgets anything to make room, and none leaves something out without
// saying so. What to do when something does not fit, to forget the kept sets or to go on without
// keeping more, is the match's to decide, in one place ([machine.hold]), and is decided again in
// every match. Only the set a walk starts in, the set the walk is in and the step between them are
// kept whatever they take, so that a walk goes on by kept steps however large its sets are.
//
// bytes is charged where room is made, with the room made: a set's states and its knownSet when it
// is kept, and the slots and the table of other steps when they grow, by what they grow by. So
// bytes is what the kept sets hold, but for the allocator's rounding.
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
	// others is where each character past ASCII leads from each kept set, where that is known.
	others otherSteps
	// kept is how many sets are kept, and bytes what they and their steps take.
	kept, bytes int
}

// forget forgets every set kept but the one a walk starts in, and every step. Every match needs
// that set, whatever it reads, so no match forgets the others to make room for it, and the set a
// match forgets them for is kept beside it. A set held across it is still the set it was, and is
// no longer looked up. Only machine.forgets calls it.
func (k *knownSets) forget() {
	first := k.first
	k.slots = nil
	k.first = nil
	k.others.forget()
	k.kept, k.bytes = 0, 0
	if first == nil {
		return
	}
	first.ascii = [utf8RuneSelf]*knownSet{}
	k.put(first)
	k.first = first
}

// setBytes is what a kept set of n states takes: its states and its knownSet.
func setBytes(n int) int {
	return 4*n + knownSetBytes
}

// slotsGrowth is what the slots grow by, in bytes, to keep one more set, or nought.
func (k *knownSets) slotsGrowth() int {
	if (k.kept+1)*2 <= len(k.slots) {
		return 0
	}
	return 8 * max(16, len(k.slots))
}

// fits is whether more bytes fit in knownBytes beside what is kept.
func (k *knownSets) fits(more int) bool {
	return k.bytes+more <= knownBytes
}

// keep is the set now holds, whose hash is hash: found where it is kept, and otherwise kept, made
// reporting so. It is nil where it does not fit beside the sets kept, unless always, which keeps it
// whatever it takes.
func (k *knownSets) keep(m *machine, now *stateSet, hash uint32, always bool) (set *knownSet, made bool) {
	if set := k.find(now, hash); set != nil {
		return set, false
	}
	if !always && !k.fits(setBytes(len(now.states()))+k.slotsGrowth()) {
		return nil, false
	}
	states := make([]int32, len(now.states()))
	for i, q := range now.states() {
		states[i] = q
	}
	set = &knownSet{states: states, hash: hash, accepts: now.has(m.accept), none: len(states) == 0}
	k.put(set)
	return set, true
}

// put keeps set, which is not kept, charging its states, its knownSet and what the slots grow by.
func (k *knownSets) put(set *knownSet) {
	if (k.kept+1)*2 > len(k.slots) {
		k.grow()
	}
	k.kept++
	set.id = uint32(k.kept)
	k.slots[k.free(set.hash)] = set
	k.bytes += setBytes(len(set.states))
}

// step is where r leads from from, or nil where that is not known.
func (k *knownSets) step(from *knownSet, r rune) *knownSet {
	if r < utf8RuneSelf {
		return from.ascii[r]
	}
	return k.others.get(from.id, r)
}

// lead keeps that r leads from from to to, both kept, and is whether it did: a step over ASCII has
// its room in from, and another is kept where what the table of other steps grows by fits, or
// always.
func (k *knownSets) lead(from *knownSet, r rune, to *knownSet, always bool) bool {
	if r < utf8RuneSelf {
		from.ascii[r] = to
		return true
	}
	more := k.others.growth()
	if !always && !k.fits(more) {
		return false
	}
	k.others.put(from.id, r, to)
	k.bytes += more
	return true
}

// otherSteps is where characters past ASCII lead from the kept sets: one table for every set,
// looked up by the set's id and the character, so what it holds is the steps worked out and no
// more. A slot holds a key, the id above the character, which is never nought, and the set the
// step leads to; a step is looked for from the slot its key's hash names to the first empty one.
// Never more than half full.
//
// The hash is the key times an odd number, drawn at random for each table, of which the slot is
// the top bits: for any two keys, few of those numbers put them in one slot, so no subject can be
// written whose steps fall in one run of slots without knowing the number. It is one
// multiplication a lookup, as a fixed hash would be.
type otherSteps struct {
	// times is the odd number keys are multiplied by, nought before the table is first made, and
	// shift what the product is shifted right by to leave a slot.
	times uint64
	shift uint
	keys  []uint64
	to    []*knownSet
	count int
}

// otherSlotBytes is what one slot of the table takes: its key and the set it leads to.
const otherSlotBytes = 16

// otherKey is the key of the step over r from the set whose id is id.
func otherKey(id uint32, r rune) uint64 {
	return uint64(id)<<32 | uint64(uint32(r))
}

// slot is the slot key is looked for from.
func (o *otherSteps) slot(key uint64) int {
	return int(key * o.times >> o.shift)
}

// get is where r leads from the set whose id is id, or nil where that is not known.
func (o *otherSteps) get(id uint32, r rune) *knownSet {
	if o.count == 0 {
		return nil
	}
	key := otherKey(id, r)
	mask := len(o.keys) - 1
	for at := o.slot(key); o.keys[at] != 0; at = (at + 1) & mask {
		if o.keys[at] == key {
			return o.to[at]
		}
	}
	return nil
}

// growth is what the table grows by, in bytes, to keep one more step, or nought.
func (o *otherSteps) growth() int {
	if (o.count+1)*2 <= len(o.keys) {
		return 0
	}
	return otherSlotBytes * max(16, len(o.keys))
}

// put keeps that r leads from the set whose id is id to to, which is not known.
func (o *otherSteps) put(id uint32, r rune, to *knownSet) {
	if (o.count+1)*2 > len(o.keys) {
		o.grow()
	}
	key := otherKey(id, r)
	mask := len(o.keys) - 1
	at := o.slot(key)
	for o.keys[at] != 0 {
		at = (at + 1) & mask
	}
	o.keys[at], o.to[at] = key, to
	o.count++
}

// grow makes twice the slots, or sixteen, and puts each step in them again by its key. What is
// gone over is the steps kept, at most what knownBytes holds.
func (o *otherSteps) grow() {
	keys, to := o.keys, o.to
	if len(keys) == 0 {
		o.times = maphash.Bytes(maphash.MakeSeed(), nil) | 1
		o.shift = 64 - 4
	} else {
		o.shift--
	}
	size := max(16, 2*len(keys))
	o.keys, o.to = make([]uint64, size), make([]*knownSet, size)
	mask := size - 1
	for i, key := range keys {
		if key == 0 {
			continue
		}
		at := o.slot(key)
		for o.keys[at] != 0 {
			at = (at + 1) & mask
		}
		o.keys[at], o.to[at] = key, to[i]
	}
}

// forget forgets every step, and the room they took.
func (o *otherSteps) forget() {
	o.keys, o.to, o.count = nil, nil, 0
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

// grow makes twice the slots, or sixteen, charging what they grow by, and puts each kept set in
// them again by its hash. What is gone over is the kept sets, at most what knownBytes holds.
func (k *knownSets) grow() {
	old := k.slots
	k.slots = make([]*knownSet, max(16, 2*len(old)))
	k.bytes += 8 * (len(k.slots) - len(old))
	for _, set := range old {
		if set != nil {
			k.slots[k.free(set.hash)] = set
		}
	}
}
