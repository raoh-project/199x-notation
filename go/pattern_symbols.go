package notation199x

import (
	"slices"
	"sort"
)

// symbols is a set of the characters a string is made of: Unicode scalar values, every code point
// but the surrogates.
//
// The universe is what text can hold. No text holds a surrogate, so no set here names one: a range
// never holds one, and the complement is taken within the scalar values. That is what lets every
// sequence of symbols a machine reads be a string.
//
// Held as ranges, sorted, apart and never touching, so that one set has one spelling. A literal is
// one code point, a class is a union of ranges, a negated class is the universe less that union,
// and . is the universe less the five line terminators: the same algebra, so nothing downstream
// needs to know which shape a set came from.
type symbols []runeRange

const (
	lastSymbol     = 0x10FFFF
	surrogatesFrom = 0xD800
	surrogatesTo   = 0xDFFF
)

func isSurrogate(r rune) bool { return r >= surrogatesFrom && r <= surrogatesTo }

var (
	// everything is every symbol there is: the scalar values.
	everything = symbols{{0, surrogatesFrom - 1}, {surrogatesTo + 1, lastSymbol}}

	// lineTerminators is what a pattern's . leaves out: a line feed, a carriage return, the
	// next-line character and the two separators.
	lineTerminators = unionOf(one('\n'), one('\r'), one(0x85), one(0x2028), one(0x2029))

	// digitSymbols is a pattern's \d: the ten ASCII digits and no other.
	digitSymbols = between('0', '9')

	// wordSymbols is a pattern's \w: the ASCII letters, the ASCII digits and the underscore.
	wordSymbols = unionOf(between('a', 'z'), between('A', 'Z'), digitSymbols, one('_'))

	// spaceSymbols is a pattern's \s: a space, a tab, a line feed, a vertical tab, a form feed and
	// a carriage return. Not the White_Space set, which is a separate set the specifications state
	// separately.
	spaceSymbols = unionOf(one(' '), between('\t', '\r'))
)

// one is the set of r alone, a scalar value.
func one(r rune) symbols { return symbols{{r, r}} }

// between is every scalar value from first to last, both ends in it, the surrogates left out.
// Both ends are scalar values, and first is not above last.
func between(first, last rune) symbols {
	return normalized(scalarsIn(first, last, nil))
}

// scalarsIn appends the runs the scalar values in first..last make, none, one or two of them.
func scalarsIn(first, last rune, out []runeRange) []runeRange {
	if first < surrogatesFrom {
		out = append(out, runeRange{first, min(last, surrogatesFrom-1)})
	}
	if last > surrogatesTo {
		out = append(out, runeRange{max(first, surrogatesTo+1), last})
	}
	return out
}

// unionOf is the symbols in any of sets.
func unionOf(sets ...symbols) symbols {
	var all []runeRange
	for _, each := range sets {
		all = append(all, each...)
	}
	return normalized(all)
}

// normalized is the runs sorted and joined where they touch or overlap, so that one set has one
// spelling.
func normalized(given []runeRange) symbols {
	sorted := slices.Clone(given)
	slices.SortFunc(sorted, func(a, b runeRange) int {
		if a.first != b.first {
			return int(a.first - b.first)
		}
		return int(a.last - b.last)
	})
	out := symbols{}
	for _, each := range sorted {
		if n := len(out); n > 0 && each.first <= out[n-1].last+1 {
			out[n-1].last = max(out[n-1].last, each.last)
			continue
		}
		out = append(out, each)
	}
	return out
}

// has is whether r is one of these: a search, since a machine asks it of every step at every
// character, and a class written wide would make a walk as long as the class.
func (s symbols) has(r rune) bool {
	i := sort.Search(len(s), func(i int) bool { return s[i].last >= r })
	return i < len(s) && s[i].first <= r
}

// not is every scalar value these do not hold.
func (s symbols) not() symbols {
	var out []runeRange
	next := rune(0)
	for _, each := range s {
		if each.first > next {
			out = scalarsIn(next, each.first-1, out)
		}
		next = each.last + 1
	}
	if next <= lastSymbol {
		out = scalarsIn(next, lastSymbol, out)
	}
	return normalized(out)
}

// less is these without those.
func (s symbols) less(those symbols) symbols {
	return unionOf(s.not(), those).not()
}

// size is how many symbols these hold.
func (s symbols) size() int64 {
	var n int64
	for _, each := range s {
		n += int64(each.last-each.first) + 1
	}
	return n
}
