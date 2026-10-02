package notation199x

import "sync"

// PatternRead is what came of reading a pattern: a [*Pattern], a [PatternRefused] or a
// [PatternBeyond], and nothing else.
//
// The three answers are about three different things. A *Pattern is a pattern of the language
// within the limits every implementation holds to. A PatternRefused is text that is no pattern of
// the language, and says what in it is not. A PatternBeyond is a pattern of the language written
// past one of those limits: every construct in it is one the language has, so an author told it
// is not in the language would go looking for a construct that is not there.
//
// A pattern read in part is not an answer: a tree of the constructs that were understood accepts
// a language the author did not write.
type PatternRead interface {
	patternRead()
}

// Pattern is a pattern that was read, as the strings it accepts.
//
// A Pattern is safe for use by several goroutines at once.
type Pattern struct {
	meaning *meaning
	once    sync.Once
	machine *machine
}

// PatternRefused is text that is no pattern of the language, and what stopped the reading.
type PatternRefused struct {
	// Why is which kind of thing it is.
	Why PatternRefusal
	// From is where in the text the construct that stopped the reading begins, in bytes.
	From int
	// Construct is the construct as written, which is empty where the text ended before a
	// construct it had begun was whole.
	Construct string
}

// PatternBeyond is a pattern of the language written past one of the limits every implementation
// holds to.
//
// Not a refusal: the language has no count, depth or size past which a pattern stops being one.
// What is past a limit is what no implementation is asked to run. Answered only of text read to
// its end and found to be a pattern, its anchors placed: text that is no pattern is
// [PatternRefused], whatever limit it also went past.
type PatternBeyond struct {
	// Limit is which limit it is past.
	Limit PatternLimit
	// From is where in the text the construct that is past it begins, in bytes; nought for
	// [MachineStates], which is a fact about the whole pattern.
	From int
	// Construct is the construct as written: the count, the group opened past the depth, or the
	// whole pattern.
	Construct string
}

func (*Pattern) patternRead()       {}
func (PatternRefused) patternRead() {}
func (PatternBeyond) patternRead()  {}

// PatternLimit is one of the limits on a pattern every implementation holds to, each the same
// number everywhere. They bound what running a pattern costs, and are stated of the text so that
// no implementation's way of running one decides which patterns it takes.
type PatternLimit uint8

const (
	// RepetitionCount is a count of a repetition, written in {n}, {n,} or {n,m}: at most
	// 134,217,727.
	RepetitionCount PatternLimit = iota
	// NestingDepth is groups one inside another: at most 200.
	NestingDepth
	// MachineStates is the states of the pattern with its repetitions written out, counted from
	// the text without building anything: at most 250,000.
	MachineStates
)

// Most is the greatest count, depth or number of states within the limit.
func (l PatternLimit) Most() int {
	switch l {
	case RepetitionCount:
		return 134_217_727
	case NestingDepth:
		return 200
	default:
		return 250_000
	}
}

// String is the limit's name.
func (l PatternLimit) String() string {
	switch l {
	case RepetitionCount:
		return "RepetitionCount"
	case NestingDepth:
		return "NestingDepth"
	default:
		return "MachineStates"
	}
}

// PatternRefusal is what makes text no pattern of the language, told apart by what an author
// wrote. The first three are text that is no pattern at all. The rest are text that would be a
// pattern in some other language and is not one in this, each for a reason of its own.
type PatternRefusal uint8

const (
	// SomethingUnclosed is a bracket, brace or parenthesis with nothing closing it, a class with
	// nothing in it, or a repetition with nothing before it to repeat.
	SomethingUnclosed PatternRefusal = iota
	// ACountThisCannotRead is a repetition whose count is no count: one with no digits, a ceiling
	// below its floor, or a run whose end comes before its start. A count past
	// [RepetitionCount] is a count, and is [PatternBeyond].
	ACountThisCannotRead
	// AnEscapeThisDoesNotRead is an escape with no meaning, or one with nothing after it.
	AnEscapeThisDoesNotRead
	// ACharacterNoStringHolds is a character no text holds: half of a surrogate pair written by
	// its number, \uD800 on its own or \x{DC00}, or bytes in the text of the pattern that are not
	// UTF-8.
	ACharacterNoStringHolds
	// AGroupTheGrammarDoesNotHave is a group beginning (? other than (?: — a lookahead, a
	// lookbehind, a named group, a flag group.
	AGroupTheGrammarDoesNotHave
	// ABackReference is a reference back to what another part of the pattern matched, which can
	// denote a set no regular language is.
	ABackReference
	// ACharacterProperty is a property of a character, \p{Alpha} or \P{...}. The language names
	// symbols by their numbers and has nothing to ask a property with.
	ACharacterProperty
	// ABoundary is \b, \B, \A, \z, \Z, \G or \R. The grammar has ^ and $ for the ends and nothing
	// else that stands between characters.
	ABoundary
	// AQuotation is \Q ... \E, which turns off the reading of what is inside it.
	AQuotation
	// AClassOfClasses is a class inside a class, or classes joined by &&.
	AClassOfClasses
	// APossessiveRepetition is ++, *+ and the rest: a repetition that gives nothing back, whose
	// strings follow from how a matcher walks, which the language does not describe.
	APossessiveRepetition
	// AnAnchorThisCannotPlace is an anchor whose answer is not a property of the pattern, as in
	// (a|)^b, where which strings are accepted is settled by which arm a string took.
	AnAnchorThisCannotPlace
)

// ReadPattern is what text means as a pattern, or what makes it no pattern, or which limit it is
// past.
//
// A limit is about a pattern, so it is answered only once the text is known to be one: a count or
// a depth past its limit is noted where it is met and the reading goes on to the end, and text
// that is no pattern anywhere in it is [PatternRefused] whatever limit it also went past. Of the
// limits, the first one met in the text, left to right, is the answer, and the states are counted
// last, of a pattern within the other two.
//
// The reading has no depth of its own: groups are read with a stack rather than by recursion, and
// the anchors are placed the same way, so text nested as deeply as it is long is read to its end.
//
// text may be any string. Bytes in it that are not UTF-8 are [ACharacterNoStringHolds].
func ReadPattern(text string) PatternRead {
	return readPattern(text)
}

// Matches is whether the whole of subject is one of the strings the pattern accepts.
//
// The subject is read a scalar value at a time, once, and never gone back over: the machine the
// pattern means is walked as the set of states it may be in, so a match takes time linear in the
// subject, for each character as many steps as the machine has at most. Where a character leads
// from a set is kept once it is worked out, so a walk that comes to the set again with the same
// character looks it up, which is what most characters of most subjects cost. subject may be any
// string, and one holding bytes that are not UTF-8 is no text and is accepted by nothing.
func (p *Pattern) Matches(subject string) bool {
	p.once.Do(func() { p.machine = build(p.meaning) })
	return p.machine.matches(subject)
}
