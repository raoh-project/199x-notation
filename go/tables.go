package notation199x

import "sort"

// mapping is a code point and the code points it maps to, sorted by the code point, as the
// generated tables hold a case mapping or a decomposition.
type mapping []mapped

type mapped struct {
	from rune
	to   []rune
}

// of is what r maps to, or nil where r is not in the mapping.
func (m mapping) of(r rune) []rune {
	i := sort.Search(len(m), func(i int) bool { return m[i].from >= r })
	if i < len(m) && m[i].from == r {
		return m[i].to
	}
	return nil
}

// rangeSet is inclusive ranges of code points, sorted and not overlapping, as the generated
// tables hold a property.
type rangeSet []runeRange

type runeRange struct {
	first, last rune
}

// has is whether r is in one of the ranges.
func (s rangeSet) has(r rune) bool {
	i := sort.Search(len(s), func(i int) bool { return s[i].last >= r })
	return i < len(s) && s[i].first <= r
}

// combining is a code point and its canonical combining class.
type combining struct {
	r     rune
	class uint8
}

// room is the bytes to make at first for an answer read from text of length bytes, held to
// longest scalar values where longest is not negative: the text's length, or the bound where that
// is less, since an answer past the bound is never written.
func room(length, longest int) int {
	if longest >= 0 && longest < length {
		return longest
	}
	return length
}
