package notation199x

// What the anchors in a pattern come to, given that the whole of it must match the whole string.
//
// Whole-string matching is what gives an anchor an answer. ^ asks to be at the start of the
// string, so it is satisfied by every string where nothing before it can take a symbol and by none
// where everything before it must: the empty string in the first case and never in the second. $
// is the same question about the end. Where neither holds, as in (a|)^b, the strings accepted are
// settled by which arm a string took, which the language has no shape for, so the pattern is
// refused. The same for an anchor under a repetition, where how many copies precede it is not a
// thing the shape says.
//
// The reader asks this of text nested as deeply as it is long, before any limit says it is too
// deep, because whether an anchor can be placed is part of whether the text is a pattern. What
// each part may and must take and whether it holds an anchor are worked out as the part is read
// (written.facts), and a part that holds none is read as what it means; so what is left is walked
// once from the root down, with a stack of its own, for where each part around an anchor stands
// and what it comes to. A pattern with no anchor is its meaning already, and is not walked.

// where is whether an anchor is at the end it is asking about, as far as the shape says.
type where uint8

const (
	whereYes where = iota
	whereNo
	whereUnsettled
)

// facts is what a part is, as far as the anchors around it ask: whether it accepts any string of
// one symbol or more, whether every string it accepts has a symbol in it, and whether it holds an
// anchor.
type facts struct {
	may, must, holds bool
}

// placement is a part to place, standing where atStart and atEnd say, or, where together, one
// whose parts are placed and whose meaning is to be put together from them.
type placement struct {
	w              *written
	atStart, atEnd where
	together       bool
}

// placeAnchors is the meaning of w with every anchor read as what it comes to, or nil where one
// cannot be settled.
func placeAnchors(w *written) *meaning {
	if w.kind == meantWritten {
		return w.meaning
	}
	tasks := []placement{{w: w, atStart: whereYes, atEnd: whereYes}}
	var results []*meaning
	for len(tasks) > 0 {
		task := tasks[len(tasks)-1]
		tasks = tasks[:len(tasks)-1]
		if task.together {
			results = putTogether(task.w, results)
			continue
		}
		switch w := task.w; w.kind {
		case meantWritten:
			results = append(results, w.meaning)
		case anchorWritten:
			at := task.atStart
			if w.end {
				at = task.atEnd
			}
			made := anchor(w.end, at)
			if made == nil {
				return nil
			}
			results = append(results, made)
		case eitherOfWritten:
			// Every arm of a choice begins where the choice begins and ends where it ends.
			tasks = append(tasks, placement{w: w, atStart: task.atStart, atEnd: task.atEnd, together: true})
			for at := len(w.parts) - 1; at >= 0; at-- {
				tasks = append(tasks, placement{w: &w.parts[at], atStart: task.atStart, atEnd: task.atEnd})
			}
		case inTurnWritten:
			tasks = append(tasks, placement{w: w, atStart: task.atStart, atEnd: task.atEnd, together: true})
			sides := sidesOf(w, task.atStart, task.atEnd)
			for at := len(w.parts) - 1; at >= 0; at-- {
				tasks = append(tasks, placement{w: &w.parts[at], atStart: sides[at][0], atEnd: sides[at][1]})
			}
		case repeatedWritten:
			switch {
			case !w.parts[0].facts.holds:
				tasks = append(tasks, placement{w: w, atStart: task.atStart, atEnd: task.atEnd, together: true})
				tasks = append(tasks, placement{w: &w.parts[0], atStart: task.atStart, atEnd: task.atEnd})
			case w.least == 1 && w.most == 1:
				// One copy is the thing itself and stands where the repetition stands.
				tasks = append(tasks, placement{w: &w.parts[0], atStart: task.atStart, atEnd: task.atEnd})
			default:
				// Any other count leaves how many copies come before the anchor to the string.
				return nil
			}
		default:
			unreachable("written", uint8(w.kind))
		}
	}
	return results[0]
}

// anchor is what an anchor standing at comes to, or nil where that cannot be settled. ^ asks to
// be at the start of the string and there is one such place, so anything that must take a symbol
// before it leaves no string at all. A $ with something after it that must take a symbol is
// refused rather than read the same way: the language keeps the set of patterns it reads, and
// that set has no pattern of this shape.
func anchor(end bool, at where) *meaning {
	switch at {
	case whereYes:
		return nothing
	case whereNo:
		if end {
			return nil
		}
		return &meaning{kind: neverMeaning}
	case whereUnsettled:
		return nil
	default:
		unreachable("where", uint8(at))
		return nil
	}
}

// putTogether takes the meanings of w's parts off the end of results, and puts w's meaning there.
func putTogether(w *written, results []*meaning) []*meaning {
	switch w.kind {
	case eitherOfWritten:
		cut := len(results) - len(w.parts)
		arms := append([]*meaning(nil), results[cut:]...)
		return append(results[:cut], &meaning{kind: eitherOfMeaning, parts: arms})
	case inTurnWritten:
		cut := len(results) - len(w.parts)
		// An anchor that asks for nothing leaves nothing in the sequence, so ^abc$ means what abc
		// means and is the same tree.
		made := inTurnMeaningOf(results[cut:])
		return append(results[:cut], made)
	case repeatedWritten:
		last := len(results) - 1
		results[last] = &meaning{kind: repeatedMeaning, parts: []*meaning{results[last]}, least: w.least, most: w.most}
		return results
	default:
		// A leaf has no parts to put together.
		unreachable("written", uint8(w.kind))
		return nil
	}
}

// sidesOf is where each part of a sequence stands: at the start of the string where nothing
// before it takes a symbol and the sequence is there, and not there where everything before it
// must; the same for the end. What stands before each part and after it is gathered once from
// each end, so that a literal written out a symbol at a time does not cost its length squared.
func sidesOf(w *written, atStart, atEnd where) [][2]where {
	count := len(w.parts)
	mayBefore := make([]bool, count+1)
	mustBefore := make([]bool, count+1)
	mustBefore[0] = true
	for at, part := range w.parts {
		mayBefore[at+1] = mayBefore[at] || part.facts.may
		mustBefore[at+1] = mustBefore[at] && part.facts.must
	}
	mayAfter := make([]bool, count+1)
	mustAfter := make([]bool, count+1)
	mustAfter[count] = true
	for at := count - 1; at >= 0; at-- {
		mayAfter[at] = mayAfter[at+1] || w.parts[at].facts.may
		mustAfter[at] = mustAfter[at+1] && w.parts[at].facts.must
	}
	out := make([][2]where, count)
	for at := range out {
		out[at] = [2]where{
			beyond(mayBefore[at], mustBefore[at], atStart),
			beyond(mayAfter[at+1], mustAfter[at+1], atEnd),
		}
	}
	return out
}

// beyond is where a part stands, given what is on that side of it and where they all stand
// together. Nothing on that side takes a symbol, so the part stands where they all do. Everything
// on that side must take one, so it does not. Anything in between and the answer belongs to a
// string rather than to the pattern.
func beyond(anyTakes, allTake bool, outer where) where {
	switch {
	case !anyTakes:
		return outer
	case allTake:
		return whereNo
	default:
		return whereUnsettled
	}
}
