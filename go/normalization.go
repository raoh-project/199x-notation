package notation199x

import (
	"slices"
	"strings"
	"sync"
	"unicode/utf8"
)

// Form is one of the four normalization forms of UAX #15.
type Form uint8

const (
	// NFC is canonical decomposition, then canonical composition.
	NFC Form = iota
	// NFD is canonical decomposition.
	NFD
	// NFKC is compatibility decomposition, then canonical composition.
	NFKC
	// NFKD is compatibility decomposition.
	NFKD
)

// formFacts is what the algorithm asks of a form.
type formFacts struct {
	name string
	// compatibility is whether the compatibility mappings are followed as well as the canonical.
	compatibility bool
	// composes is whether canonical composition follows the decomposition.
	composes bool
	// trivialLimit is the code point below which every code point is its own normalization in
	// the form, so text made only of those is.
	trivialLimit rune
}

var forms = [...]formFacts{
	NFC:  {"NFC", false, true, nfcTrivialLimit},
	NFD:  {"NFD", false, false, nfdTrivialLimit},
	NFKC: {"NFKC", true, true, nfkcTrivialLimit},
	NFKD: {"NFKD", true, false, nfkdTrivialLimit},
}

// facts is what the algorithm asks of f, which panics where f is none of the four forms.
func (f Form) facts() *formFacts {
	if int(f) >= len(forms) {
		noneOf("Form", uint8(f))
	}
	return &forms[f]
}

// String is the form's name, NFC, NFD, NFKC or NFKD, or Form(n) where it is none of them.
func (f Form) String() string {
	if int(f) < len(forms) {
		return forms[f].name
	}
	return nameOf(nil, "Form", uint8(f))
}

// Normalize is s in form, by Unicode 18.0.0's data.
//
// Not golang.org/x/text/unicode/norm, which answers for the Unicode version it was built with, and
// moves with a dependency update.
//
// The algorithm is the standard three steps of UAX #15: decompose fully, by the tables and by
// Hangul's arithmetic, put combining marks in canonical order, and in a composing form compose
// canonically wherever nothing blocks it. The compatibility forms decompose by the compatibility
// mappings as well as the canonical ones; composition is canonical in every form. s is valid UTF-8
// (see [InvalidUTF8At]). Normalize panics where form is none of the four forms.
func Normalize(form Form, s string) string {
	normalized, _ := normalize(form, s, -1)
	return normalized
}

// NormalizeWithin is [Normalize] where that is no longer than longest scalar values, and false
// where it is longer, which is found out before more than longest is written. A negative bound is
// one no text is within. NormalizeWithin panics where form is none of the four forms.
func NormalizeWithin(form Form, s string, longest int) (string, bool) {
	form.facts()
	if longest < 0 {
		return "", false
	}
	return normalize(form, s, longest)
}

// normalize is s in form, and false where that is longer than longest; a negative longest is no
// bound.
//
// Text made only of code points below the form's trivial limit is its own normalization, and is
// answered with itself. Other text is normalized from the last code point below the limit before
// the first one that is not, and what comes before that is kept as it is: it is its own
// normalization, and nothing from there on reaches back into it, since the code point there is a
// starter that composes with nothing before it and blocks every mark after it from composing with
// a starter before it. That code point is normalized with the rest, since what follows it may
// compose with it.
func normalize(form Form, s string, longest int) (string, bool) {
	facts := form.facts()
	limit := facts.trivialLimit
	last, beforeLast, read := 0, 0, 0
	for at := 0; at < len(s); {
		r, size := utf8.DecodeRuneInString(s[at:])
		if r >= limit {
			return normalizeFrom(facts, s, last, beforeLast, longest)
		}
		last = at
		beforeLast = read
		read++
		at += size
	}
	if longest >= 0 && read > longest {
		return "", false
	}
	return s, true
}

// normalizeFrom is the algorithm from the text's start, taking the text before from, kept scalar
// values long, as it is.
//
// The three steps are taken one combining run at a time, as the text is read: each code point is
// decomposed as it arrives, the marks after a starter are held until the next starter, and then
// they are put in canonical order and, in a composing form, composed into it. Canonical ordering
// never moves a mark past a starter, and composition joins a starter only to the marks after it
// or, where nothing is between them, to the starter after it, so a run settled when the next
// starter arrives is settled as the whole text's algorithm would settle it. What is held at once
// is one run's marks, never the decomposition of the whole text.
func normalizeFrom(form *formFacts, s string, from, kept, longest int) (string, bool) {
	if longest >= 0 && kept > longest {
		return "", false
	}
	derived := derivedTables()
	compatibility := form.compatibility
	inert := derived.inertCanonical
	if compatibility {
		inert = derived.inertCompatibility
	}
	c := composing{composes: form.composes, compositions: derived.compositions, longest: longest, starter: -1}
	c.out.Grow(room(len(s), longest))
	c.out.WriteString(s[:from])
	c.written = kept
	// What one code point decomposes into, in room the whole text shares, as long as the longest
	// decomposition there is so that it is made once.
	parts := make([]rune, 0, derived.longestDecomposition)
	for at := from; at < len(s); {
		r, size := utf8.DecodeRuneInString(s[at:])
		at += size
		if inert.has(r) {
			if !c.takeInert(r) {
				return "", false
			}
			continue
		}
		var decomposed bool
		if parts, decomposed = decomposeInto(parts[:0], r, compatibility); !decomposed {
			if !c.take(r) {
				return "", false
			}
			continue
		}
		for _, part := range parts {
			if !c.take(part) {
				return "", false
			}
		}
	}
	if !c.write(c.settle()) {
		return "", false
	}
	return c.out.String(), true
}

// fewMarks is how many marks a run may hold before they are put in order by counting rather than
// by insertion, which is quadratic in the run.
const fewMarks = 32

// composing is one pass of canonical ordering and, where the form composes, composition over code
// points already decomposed: the starter of the run it is in, the marks held after it, and what is
// settled.
type composing struct {
	composes     bool
	compositions map[[2]rune]rune
	longest      int
	out          strings.Builder
	written      int
	starter      rune // -1 where the run has none
	marks        []rune
}

// take takes the next decomposed code point, and is false where what is written has passed
// longest.
func (c *composing) take(r rune) bool {
	if combiningClass(r) != 0 {
		c.marks = append(c.marks, r)
		return true
	}
	kept := c.settle()
	if c.composes && c.starter >= 0 && kept == 0 {
		if composed, ok := c.compose(c.starter, r); ok {
			c.starter = composed
			return true
		}
	}
	if !c.write(kept) {
		return false
	}
	c.starter = r
	return true
}

// takeInert is take of a code point that composes with nothing before it and decomposes into
// nothing, so it ends the run and starts the next, and no table is asked.
func (c *composing) takeInert(r rune) bool {
	if !c.write(c.settle()) {
		return false
	}
	c.starter = r
	return true
}

// settle puts the held marks in canonical order and, where the form composes, composes into the
// starter each one nothing blocks, and answers how many marks are left after the starter.
func (c *composing) settle() int {
	if len(c.marks) > 1 {
		c.order()
	}
	if !c.composes || c.starter < 0 {
		return len(c.marks)
	}
	kept := 0
	lastClass := -1
	for _, mark := range c.marks {
		class := int(combiningClass(mark))
		if lastClass < class {
			if composed, ok := c.compose(c.starter, mark); ok {
				c.starter = composed
				continue
			}
		}
		c.marks[kept] = mark
		kept++
		lastClass = class
	}
	c.marks = c.marks[:kept]
	return kept
}

// order puts the held marks in canonical order: stable, by combining class.
func (c *composing) order() {
	if len(c.marks) <= fewMarks {
		for i := 1; i < len(c.marks); i++ {
			mark := c.marks[i]
			class := combiningClass(mark)
			j := i
			for j > 0 && combiningClass(c.marks[j-1]) > class {
				c.marks[j] = c.marks[j-1]
				j--
			}
			c.marks[j] = mark
		}
		return
	}
	var starts [257]int
	for _, mark := range c.marks {
		starts[int(combiningClass(mark))+1]++
	}
	for class := 1; class < len(starts); class++ {
		starts[class] += starts[class-1]
	}
	ordered := make([]rune, len(c.marks))
	for _, mark := range c.marks {
		class := combiningClass(mark)
		ordered[starts[class]] = mark
		starts[class]++
	}
	c.marks = ordered
}

// write writes the starter and the kept marks after it, and empties the run.
func (c *composing) write(kept int) bool {
	if c.starter >= 0 && !c.writeOne(c.starter) {
		return false
	}
	for _, mark := range c.marks[:kept] {
		if !c.writeOne(mark) {
			return false
		}
	}
	c.starter = -1
	c.marks = c.marks[:0]
	return true
}

func (c *composing) writeOne(r rune) bool {
	if c.longest >= 0 && c.written >= c.longest {
		return false
	}
	c.out.WriteRune(r)
	c.written++
	return true
}

// Hangul's algorithmic decomposition and composition, as UAX #15 states them.
const (
	hangulSBase  = 0xAC00
	hangulLBase  = 0x1100
	hangulVBase  = 0x1161
	hangulTBase  = 0x11A7
	hangulLCount = 19
	hangulVCount = 21
	hangulTCount = 28
	hangulNCount = hangulVCount * hangulTCount
	hangulSCount = hangulLCount * hangulNCount
)

func isHangulSyllable(r rune) bool {
	return r >= hangulSBase && r < hangulSBase+hangulSCount
}

// decomposeInto appends r's full decomposition to dst, and is false, dst as it was, where r is its
// own: Hangul's arithmetic split, or the tables followed as far as they go, since a decomposition
// may map to code points that decompose themselves. With compatibility the compatibility mappings
// are followed as well.
func decomposeInto(dst []rune, r rune, compatibility bool) ([]rune, bool) {
	if isHangulSyllable(r) {
		index := r - hangulSBase
		dst = append(dst, hangulLBase+index/hangulNCount, hangulVBase+(index%hangulNCount)/hangulTCount)
		if t := hangulTBase + index%hangulTCount; t != hangulTBase {
			dst = append(dst, t)
		}
		return dst, true
	}
	mapped := canonicalDecomposition.of(r)
	if mapped == nil && compatibility {
		mapped = compatibilityDecomposition.of(r)
	}
	if mapped == nil {
		return dst, false
	}
	for _, part := range mapped {
		var further bool
		if dst, further = decomposeInto(dst, part, compatibility); !further {
			dst = append(dst, part)
		}
	}
	return dst, true
}

// combiningClass is r's canonical combining class: 0 for a starter, and for a mark the class
// canonical ordering sorts it by. No Hangul jamo or syllable has one other than 0.
func combiningClass(r rune) uint8 {
	// No code point below the first one the table names has a class, which is most text.
	if len(combiningClasses) == 0 || r < combiningClasses[0].r {
		return 0
	}
	low, high := 0, len(combiningClasses)
	for low < high {
		mid := int(uint(low+high) >> 1)
		if combiningClasses[mid].r < r {
			low = mid + 1
		} else {
			high = mid
		}
	}
	if low < len(combiningClasses) && combiningClasses[low].r == r {
		return combiningClasses[low].class
	}
	return 0
}

// derived is what normalization works out from the generated tables once, the first time text is
// normalized past its trivial limit, so that a program that never does pays nothing for it.
type derived struct {
	compositions                       map[[2]rune]rune
	inertCanonical, inertCompatibility *codePointBits
	// longestDecomposition is the most code points one code point decomposes into fully, in any
	// form.
	longestDecomposition int
}

var derivedTables = sync.OnceValue(func() *derived {
	inert := inertCanonical()
	longest := 0
	for _, table := range []mapping{canonicalDecomposition, compatibilityDecomposition} {
		for _, each := range table {
			parts, _ := decomposeInto(nil, each.from, true)
			longest = max(longest, len(parts))
		}
	}
	return &derived{
		compositions:         compositions(),
		inertCanonical:       inert,
		inertCompatibility:   inertCompatibility(inert),
		longestDecomposition: longest,
	}
})

// compositions is the pair-composition table, inverted from canonicalDecomposition rather than
// kept as a generated table of its own: every two-member canonical decomposition whose first
// member is a starter and whose result is not a script-specific exclusion. The other two
// Full_Composition_Exclusion categories, singleton and non-starter decompositions, are read off
// the decomposition and the combining classes themselves, so decomposition and composition cannot
// disagree.
func compositions() map[[2]rune]rune {
	pairs := make(map[[2]rune]rune)
	for _, each := range canonicalDecomposition {
		if len(each.to) == 2 && combiningClass(each.to[0]) == 0 {
			if _, excluded := slices.BinarySearch(scriptSpecificExclusions, each.from); !excluded {
				pairs[[2]rune{each.to[0], each.to[1]}] = each.from
			}
		}
	}
	return pairs
}

// compose is the primary composite of starter followed by r, and false where the pair does not
// compose: Hangul's L+V and LV+T, or the table.
func (c *composing) compose(starter, r rune) (rune, bool) {
	if starter >= hangulLBase && starter < hangulLBase+hangulLCount &&
		r >= hangulVBase && r < hangulVBase+hangulVCount {
		return hangulSBase + ((starter-hangulLBase)*hangulVCount+(r-hangulVBase))*hangulTCount, true
	}
	if isHangulSyllable(starter) && (starter-hangulSBase)%hangulTCount == 0 &&
		r > hangulTBase && r < hangulTBase+hangulTCount {
		return starter + (r - hangulTBase), true
	}
	composed, ok := c.compositions[[2]rune{starter, r}]
	return composed, ok
}

// codePointBits is one bit for each code point.
type codePointBits [(utf8.MaxRune + 1) / 64]uint64

func (b *codePointBits) has(r rune) bool { return b[r>>6]&(1<<(r&63)) != 0 }

func (b *codePointBits) clear(r rune) { b[r>>6] &^= 1 << (r & 63) }

// inertCanonical is the code points that are inert in a canonical form: starters that have no
// decomposition and are the second member of no composition, Hangul's included. Each composes with
// nothing before it, and no table is asked to know that. Most text in a script written without
// combining marks is made of them.
func inertCanonical() *codePointBits {
	bits := new(codePointBits)
	for i := range bits {
		bits[i] = ^uint64(0)
	}
	for _, each := range canonicalDecomposition {
		bits.clear(each.from)
		// What follows a starter in a composition is the second member of a pair.
		if len(each.to) == 2 {
			bits.clear(each.to[1])
		}
	}
	for _, each := range combiningClasses {
		bits.clear(each.r)
	}
	for r := rune(hangulSBase); r < hangulSBase+hangulSCount; r++ {
		bits.clear(r)
	}
	for r := rune(hangulVBase); r < hangulVBase+hangulVCount; r++ {
		bits.clear(r)
	}
	for r := rune(hangulTBase); r < hangulTBase+hangulTCount; r++ {
		bits.clear(r)
	}
	return bits
}

// inertCompatibility is inertCanonical for a compatibility form: without the code points a
// compatibility mapping decomposes.
func inertCompatibility(inertCanonical *codePointBits) *codePointBits {
	bits := *inertCanonical
	for _, each := range compatibilityDecomposition {
		bits.clear(each.from)
	}
	return &bits
}
