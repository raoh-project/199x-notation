package notation199x

// mapping is a code point and the code points it maps to, sorted by the code point, as the
// generated tables hold a case mapping or a decomposition.
type mapping []mapped

type mapped struct {
	from rune
	to   []rune
}

// of is what r maps to, or nil where r is not in the mapping. A search written out rather than
// sort.Search, whose call through a function value a lookup a character pays for.
func (m mapping) of(r rune) []rune {
	low, high := 0, len(m)
	for low < high {
		mid := int(uint(low+high) >> 1)
		if m[mid].from < r {
			low = mid + 1
		} else {
			high = mid
		}
	}
	if low < len(m) && m[low].from == r {
		return m[low].to
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
	return inRanges(s, r)
}

// inRanges is whether r is in one of ranges, sorted and apart: the first range that ends at or
// after r, searched for, holds it or nothing does.
func inRanges(ranges []runeRange, r rune) bool {
	low, high := 0, len(ranges)
	for low < high {
		mid := int(uint(low+high) >> 1)
		if ranges[mid].last < r {
			low = mid + 1
		} else {
			high = mid
		}
	}
	return low < len(ranges) && ranges[low].first <= r
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
