package notation199x

import "unicode/utf8"

// meaning is what a pattern means: the set of strings it accepts, as regular-language operations
// over sets of scalar values. The one form a pattern takes past its reader; the machine is built
// from it and never from the text.
//
// What is kept is what the language depends on and nothing else. One written character is one
// symbol, a set of one, and a literal, a class, a negated class, a shorthand and . all arrive as
// symbols, told apart only by the set. An anchor is gone: whole-string matching settles what each
// comes to where the pattern is read. Whether a repetition is greedy or reluctant and whether a
// group captures say what an engine does on the way and not which strings come out, so none of
// them is here either.
type meaning struct {
	kind meaningKind
	// held is the set of a symbolsMeaning.
	held symbols
	// parts is what an inTurnMeaning has one after another, the arms of an eitherOfMeaning, two
	// or more, and what a repeatedMeaning repeats, alone.
	parts []*meaning
	// least and most are the fewest and the most times of a repeatedMeaning; most is noCeiling
	// for a repetition nothing caps.
	least, most int
}

type meaningKind uint8

const (
	// nothingMeaning is the one string of no symbols.
	nothingMeaning meaningKind = iota
	// neverMeaning is no string at all, which is what an anchor nobody can satisfy leaves.
	neverMeaning
	// symbolsMeaning is one symbol out of a set of them.
	symbolsMeaning
	// inTurnMeaning is its parts one after another.
	inTurnMeaning
	// eitherOfMeaning is any one of its arms, every arm and not the first.
	eitherOfMeaning
	// repeatedMeaning is the same thing some number of times over, both ends carried.
	repeatedMeaning
)

// noCeiling is what *, + and {n,} put where a repetition's most is: a bound nothing reaches
// rather than a large one.
const noCeiling = -1

// written is a pattern as its reader holds it before the anchors are placed. The one shape here a
// meaning does not have is the anchor, whose answer is settled by where it stands among the rest
// of the pattern, which is not known until the whole of it is read. Everything else is already its
// meaning: a part that holds no anchor is made a meantWritten as it is read (inTurnOf, eitherOfOf,
// repeatedOf), so a pattern with no anchor is one meantWritten once it is read, and only the parts
// around an anchor are left as anything else.
//
// What the anchors around a part ask of it, and the states it is counted at, are worked out as it
// is made, from its parts', which were made before it: nothing walks the tree to find them, and
// nothing recurses however deep the text nests.
//
// Held as a value, and its parts in a slice of them: a character read is a place in the slice of
// the sequence it is in, and nothing is made for it but its meaning.
type written struct {
	// meaning is what a meantWritten means.
	meaning *meaning
	// parts are as a meaning's are.
	parts       []written
	least, most int
	// states is the states it is counted at ([writtenStates]), up to pastStates.
	states int64
	kind   writtenKind
	// end is whether an anchorWritten is $ rather than ^.
	end   bool
	facts facts
}

type writtenKind uint8

const (
	meantWritten writtenKind = iota
	anchorWritten
	inTurnWritten
	eitherOfWritten
	repeatedWritten
)

var nothing = &meaning{kind: nothingMeaning}

// meant is a leaf the reader reads, nothing or a set of symbols.
func meant(m *meaning) written {
	takes := m.kind == symbolsMeaning
	var states int64
	if takes {
		states = 1
	}
	return written{kind: meantWritten, meaning: m, facts: facts{may: takes, must: takes}, states: states}
}

// anchorOf is ^, or $ where end.
func anchorOf(end bool) written {
	return written{kind: anchorWritten, end: end, facts: facts{holds: true}, states: 1}
}

// literalMeaning is the meaning of the one character r written as itself: for ASCII, one of
// asciiLiterals, which every pattern shares, since nothing writes to a meaning once it is made; for
// any other, the meaning and the one run its set is, made together.
func literalMeaning(r rune) *meaning {
	if r < utf8.RuneSelf {
		return asciiLiterals[r]
	}
	return newLiteral(r)
}

// literal is a meaning of one character with the run its set is held in beside it, so that the
// two are made at once.
type literal struct {
	meaning meaning
	run     [1]runeRange
}

func newLiteral(r rune) *meaning {
	l := &literal{run: [1]runeRange{{r, r}}}
	l.meaning = meaning{kind: symbolsMeaning, held: l.run[:]}
	return &l.meaning
}

// asciiLiterals is the meaning of each ASCII character written as itself.
var asciiLiterals = func() (out [utf8.RuneSelf]*meaning) {
	for r := range rune(utf8.RuneSelf) {
		out[r] = newLiteral(r)
	}
	return out
}()

// inTurnOf is parts one after another, two or more, none of them nothing. Where none holds an
// anchor it is what they mean in turn, put together as placing the anchors puts a sequence
// together (putTogether).
func inTurnOf(parts []written) written {
	var f facts
	var states int64
	for _, part := range parts {
		f.may = f.may || part.facts.may
		f.must = f.must || part.facts.must
		f.holds = f.holds || part.facts.holds
		states = plusStates(states, part.states)
	}
	if !f.holds {
		return written{kind: meantWritten, meaning: inTurnMeaningOf(meaningsOf(parts)), facts: f,
			states: states}
	}
	return written{kind: inTurnWritten, parts: parts, facts: f, states: states}
}

// eitherOfOf is any one of arms, two or more; what they mean where none holds an anchor.
func eitherOfOf(arms []written) written {
	f := facts{must: true}
	states := int64(1)
	for _, arm := range arms {
		f.may = f.may || arm.facts.may
		f.must = f.must && arm.facts.must
		f.holds = f.holds || arm.facts.holds
		states = plusStates(states, plusStates(1, arm.states))
	}
	if !f.holds {
		return written{kind: meantWritten, meaning: &meaning{kind: eitherOfMeaning, parts: meaningsOf(arms)},
			facts: f, states: states}
	}
	return written{kind: eitherOfWritten, parts: arms, facts: f, states: states}
}

// repeatedOf is one from least to most times; what that means where one holds no anchor.
func repeatedOf(one written, least, most int) written {
	f := facts{
		may:   (most == noCeiling || most > 0) && one.facts.may,
		must:  least > 0 && one.facts.must,
		holds: one.facts.holds,
	}
	states := repeatedStates(one.states, least, most)
	if !f.holds {
		return written{kind: meantWritten, facts: f, states: states,
			meaning: &meaning{kind: repeatedMeaning, parts: []*meaning{one.meaning}, least: least, most: most}}
	}
	return written{kind: repeatedWritten, parts: []written{one}, least: least, most: most, facts: f,
		states: states}
}

// meaningsOf is what each of ws means, every one of them a meantWritten: a part that holds no
// anchor is one as it is made.
func meaningsOf(ws []written) []*meaning {
	out := make([]*meaning, len(ws))
	for at, w := range ws {
		if w.kind != meantWritten {
			unreachable("written holding no anchor", uint8(w.kind))
		}
		out[at] = w.meaning
	}
	return out
}

// inTurnMeaningOf is parts one after another, with every part that is nothing left out: no part
// is nothing, nothing alone, and one part itself.
func inTurnMeaningOf(made []*meaning) *meaning {
	parts := make([]*meaning, 0, len(made))
	for _, one := range made {
		if one.kind != nothingMeaning {
			parts = append(parts, one)
		}
	}
	switch len(parts) {
	case 0:
		return nothing
	case 1:
		return parts[0]
	default:
		return &meaning{kind: inTurnMeaning, parts: parts}
	}
}
