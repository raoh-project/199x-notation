package notation199x

// The states a pattern comes to with its repetitions written out, counted from what is written
// and without building anything. This is the measure [MachineStates] is stated in, and it is the
// specifications' and not this implementation's: every implementation counts the same number
// from the same text. Counted on the pattern as written:
//
//   - a character, an escape, ., a shorthand and a class are one each; so are ^ and $;
//   - an empty pattern, group or alternative is none;
//   - a sequence is the sum of its parts, and a group is what is inside it;
//   - a choice of n alternatives is one more than the sum of one more than each alternative;
//   - A{n,m} is m times A, plus one; A{n} is A{n,n} and A? is A{0,1};
//   - A{n,} is n + 1 times A, plus one; A* is A{0,} and A+ is A{1,};
//   - the pattern is one more than what it is written as.
//
// For a pattern without anchors this is the states of the machine its shape builds, and an
// anchor counts one and comes to at most one state, so a pattern within the limit always has a
// machine. Counted up to one past the limit and no further, so a count as large as the reader
// reads is multiplied without overflowing.
//
// Asked only of a pattern within the nesting depth, so the walk recurses.

// pastStates is one past the limit: every count above the limit is this.
var pastStates = int64(MachineStates.Most()) + 1

// writtenStates is the states w comes to as a whole pattern, or pastStates.
func writtenStates(w *written) int64 {
	return plusStates(1, statesIn(w))
}

func statesIn(w *written) int64 {
	switch w.kind {
	case meantWritten:
		return meaningStatesIn(w.meaning)
	case anchorWritten:
		return 1
	case inTurnWritten:
		var sum int64
		for _, part := range w.parts {
			sum = plusStates(sum, statesIn(part))
		}
		return sum
	case eitherOfWritten:
		sum := int64(1)
		for _, arm := range w.parts {
			sum = plusStates(sum, plusStates(1, statesIn(arm)))
		}
		return sum
	case repeatedWritten:
		return repeatedStates(statesIn(w.parts[0]), w.least, w.most)
	default:
		unreachable("written", uint8(w.kind))
		return 0
	}
}

// meaningStatesIn is the states of a meaning the reader holds before the anchors are placed,
// which is a set of symbols or nothing.
func meaningStatesIn(m *meaning) int64 {
	switch m.kind {
	case nothingMeaning:
		return 0
	case symbolsMeaning:
		return 1
	default:
		unreachable("meaning the reader writes", uint8(m.kind))
		return 0
	}
}

// repeatedStates is a repetition of a body of body states: its copies, and the state it ends in.
func repeatedStates(body int64, least, most int) int64 {
	copies := int64(most)
	if most == noCeiling {
		copies = int64(least) + 1
	}
	return plusStates(timesStates(copies, body), 1)
}

func plusStates(one, other int64) int64 {
	return min(pastStates, one+other)
}

func timesStates(copies, body int64) int64 {
	if copies == 0 || body == 0 {
		return 0
	}
	if copies > pastStates/body {
		return pastStates
	}
	return min(pastStates, copies*body)
}
