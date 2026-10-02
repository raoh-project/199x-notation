package notation199x

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
// meaning.
type written struct {
	kind writtenKind
	// meaning is what a meantWritten means.
	meaning *meaning
	// end is whether an anchorWritten is $ rather than ^.
	end bool
	// parts are as a meaning's are.
	parts       []*written
	least, most int
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

func meant(m *meaning) *written { return &written{kind: meantWritten, meaning: m} }
