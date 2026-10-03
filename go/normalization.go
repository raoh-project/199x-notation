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
	// trivialLimit is the code point below which every code point is a stable starter of the
	// form, which is known without asking a table. Every form's is past ASCII and before the
	// surrogates, which the generator checks.
	trivialLimit rune
	// stableBit is the form's bit of stablePages.
	stableBit uint8
}

var forms = [...]formFacts{
	NFC:  {"NFC", false, true, nfcTrivialLimit, 1},
	NFD:  {"NFD", false, false, nfdTrivialLimit, 2},
	NFKC: {"NFKC", true, true, nfkcTrivialLimit, 4},
	NFKD: {"NFKD", true, false, nfkdTrivialLimit, 8},
}

// stable is whether r is a stable starter of the form: a starter whose quick check for the form
// is Yes. Text made only of them is its own normalization in the form, and one of them ends what
// comes before it: no mark after it is put in order before it or composes with a starter before
// it, and it composes with nothing before it.
func (f *formFacts) stable(r rune) bool {
	return r < f.trivialLimit || stablePages[int(stableBlocks[r>>8])<<8|int(r&0xFF)]&f.stableBit != 0
}

// stableUpTo is where the stable starters s has from at end: the first code point from there that
// is not one, or the end of the text; how many code points it went past; and where the last of
// them begins, which is at where it went past none.
func (f *formFacts) stableUpTo(s string, at int) (end, count, last int) {
	last = at
	for at < len(s) {
		if c := s[at]; c < utf8.RuneSelf {
			last = at
			at++
			count++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[at:])
		if !f.stable(r) {
			break
		}
		last = at
		at += size
		count++
	}
	return at, count, last
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
// Text that is its own normalization is answered with itself. The text is kept as it is up to the
// first code point that is not a stable starter of the form. From the stable starter before that
// one, which what follows it may compose with, the algorithm is run up to the next stable starter,
// and the text is kept as it is again from there. A stable starter composes with nothing before it
// and blocks every mark after it from reaching a starter before it, so what comes before it is
// settled when it is read. A run is written into the answer only where it changed what it went
// over, and the answer is made only once one has: up to there it is the text.
func normalize(form Form, s string, longest int) (string, bool) {
	facts := form.facts()
	var out strings.Builder
	// changed is whether a run has changed what it went over, and out holds the answer up to kept.
	changed := false
	kept, start := 0, 0
	// before is the code points of the answer before the last run the algorithm went over, and
	// read the stable starters read since, the last of them at start where start is before at.
	before, read := 0, 0
	var c *composing
	for at := 0; at < len(s); {
		if end, count, last := facts.stableUpTo(s, at); end > at {
			read += count
			start = last
			at = end
			if at == len(s) {
				break
			}
		}
		if start < at {
			read--
		}
		if c == nil {
			c = newComposing(facts, longest)
		}
		end, ok := c.run(s, start, at, before+read)
		if !ok {
			return "", false
		}
		if string(c.out) != s[start:end] {
			if !changed {
				out.Grow(room(len(s), longest))
				changed = true
			}
			out.WriteString(s[kept:start])
			out.Write(c.out)
			kept = end
		}
		before = c.written
		read = 0
		start = end
		at = end
	}
	if longest >= 0 && before+read > longest {
		return "", false
	}
	if !changed {
		return s, true
	}
	out.WriteString(s[kept:])
	return out.String(), true
}

// fewMarks is how many marks a run may hold before they are put in order by counting rather than
// by insertion, which is quadratic in the run.
const fewMarks = 32

// composing is one pass of canonical ordering and, where the form composes, composition over code
// points already decomposed: the starter of the run it is in, the marks held after it, and what is
// settled.
type composing struct {
	form         *formFacts
	compositions map[[2]rune]rune
	longest      int
	// out is what run wrote, the run settled.
	out []byte
	// written is how many code points of the answer come before what is held: those before the
	// run, and those it has written.
	written int
	starter rune // -1 where the run has none
	marks   []rune
	// parts is what one code point decomposes into.
	parts []rune
	// Room each of out, marks and parts starts in, made with the composing, so that a run that
	// fits it allocates nothing of its own. Every decomposition fits partsRoom.
	outRoom   [64]byte
	marksRoom [8]rune
	partsRoom [longestDecomposition]rune
}

func newComposing(form *formFacts, longest int) *composing {
	c := &composing{form: form, compositions: compositionPairs(), longest: longest, starter: -1}
	c.out = c.outRoom[:0]
	c.marks = c.marksRoom[:0]
	c.parts = c.partsRoom[:0]
	return c
}

// run runs the algorithm over s from start, before code points of the answer coming before it, up
// to the first stable starter after at, or to the end of the text where none comes, and writes the
// run settled into out. The code points from start to at are taken, and so is the one there,
// whatever they are; with at the end of the text, all of it from start is. It is where it stopped,
// and false where the answer has passed longest.
func (c *composing) run(s string, start, at int, before int) (int, bool) {
	c.out = c.out[:0]
	c.written = before
	j := start
	for j < len(s) {
		r, size := utf8.DecodeRuneInString(s[j:])
		if j > at && c.form.stable(r) {
			break
		}
		j += size
		var decomposed bool
		if c.parts, decomposed = decomposeInto(c.parts[:0], r, c.form.compatibility); !decomposed {
			if !c.take(r) {
				return 0, false
			}
			continue
		}
		for _, part := range c.parts {
			if !c.take(part) {
				return 0, false
			}
		}
	}
	if !c.write(c.settle()) {
		return 0, false
	}
	return j, true
}

// take takes the next decomposed code point, and is false where what is written has passed
// longest.
func (c *composing) take(r rune) bool {
	if combiningClass(r) != 0 {
		c.marks = append(c.marks, r)
		return true
	}
	kept := c.settle()
	if c.form.composes && c.starter >= 0 && kept == 0 {
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

// settle puts the held marks in canonical order and, where the form composes, composes into the
// starter each one nothing blocks, and answers how many marks are left after the starter.
func (c *composing) settle() int {
	if len(c.marks) > 1 {
		c.order()
	}
	if !c.form.composes || c.starter < 0 {
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
	c.out = utf8.AppendRune(c.out, r)
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
	return combiningClassPages[int(combiningClassBlocks[r>>8])<<8|int(r&0xFF)]
}

// compositionPairs is the pair-composition table, worked out the first time the algorithm runs, so
// that a program that never runs it pays nothing for it.
var compositionPairs = sync.OnceValue(compositions)

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
