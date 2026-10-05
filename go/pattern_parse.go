package notation199x

import (
	"strings"
	"unicode/utf8"
)

// endOfText is what peek answers past the last byte, outside every value a byte has. U+0000 is a
// character a pattern may write, and stands for itself like any other.
const endOfText = -1

// patternReader is the reader of the pattern language, and the only one: what it hands on is the
// meaning, and nothing after it reads the text again.
type patternReader struct {
	text  string
	at    int
	depth int
	// construct is where the construct being read begins, which is what a refusal quotes.
	construct int
	// past is the first limit met in the text, or nil while none has been.
	past *PatternBeyond
}

// refusal is what the reader panics with where the text is no pattern, carried to the one place
// that answers.
type refusal struct {
	why      PatternRefusal
	from, to int
}

func readPattern(text string) (read PatternRead) {
	r := &patternReader{text: text}
	defer func() {
		if recovered := recover(); recovered != nil {
			refused, ok := recovered.(refusal)
			if !ok {
				panic(recovered)
			}
			to := min(len(text), max(refused.to, refused.from))
			read = PatternRefused{Why: refused.why, From: refused.from, Construct: text[refused.from:to]}
		}
	}()
	w := r.pattern()
	// Every anchor has to come to something, and what it comes to is settled by where it stands,
	// which is known now that the whole of the pattern is.
	m := placeAnchors(&w)
	if m == nil {
		return PatternRefused{Why: AnAnchorThisCannotPlace, From: 0, Construct: text}
	}
	// The text is a pattern. Whether it is one every implementation takes is asked now.
	if r.past != nil {
		return *r.past
	}
	// Counted on what was written, where an anchor is one state whatever it came to, so the count
	// is never below the states of the machine the meaning builds.
	if writtenStates(&w) > int64(MachineStates.Most()) {
		return PatternBeyond{Limit: MachineStates, From: 0, Construct: text}
	}
	return &Pattern{compiled: &compiled{meaning: m}}
}

// open is a choice being read, in a group or at the top: the arms read so far, and the parts of
// the one being read.
//
// The parts read since the last that holds an anchor hold none, and are held in run as what they
// mean, with what they are together. Characters of one symbol each read one after another are
// held in chars, a run of one apiece, and go into run as one meaning when a part of another kind
// comes (settle). Where a part holding an anchor comes, the run goes into parts as one (flush), so
// the parts of a sequence are the ones holding an anchor and the runs between them.
type open struct {
	arms, parts []written
	run         []*meaning
	chars       []runeRange
	// runFacts is what the run is as a sequence, and every whether each of its parts must take a
	// symbol; runStates the states they come to together.
	runFacts  facts
	runStates int64
}

// arm is the arm being read: an arm of one part is that part, and an arm of none is nothing. One
// with no part holding an anchor is what its run means.
func (o *open) arm() written {
	o.settle()
	if len(o.parts) == 0 {
		switch len(o.run) {
		case 0:
			return meant(nothing)
		case 1:
			return o.runPart()
		default:
			return written{kind: meantWritten, meaning: &meaning{kind: inTurnMeaning, parts: o.run},
				facts: o.runFacts, states: o.runStates}
		}
	}
	o.flush()
	if len(o.parts) == 1 {
		return o.parts[0]
	}
	return inTurnOf(o.parts)
}

// runPart is the one part the run holds.
func (o *open) runPart() written {
	return written{kind: meantWritten, meaning: o.run[0], facts: o.runFacts, states: o.runStates}
}

// flush puts the run into the parts, as the one part it is or as a runWritten, and starts another.
func (o *open) flush() {
	o.settle()
	switch len(o.run) {
	case 0:
		return
	case 1:
		o.parts = appended(o.parts, o.runPart())
	default:
		o.parts = appended(o.parts, written{kind: runWritten,
			meaning: &meaning{kind: inTurnMeaning, parts: o.run}, facts: o.runFacts, states: o.runStates})
	}
	o.run = nil
	o.runFacts = facts{}
	o.runStates = 0
}

// next starts the next arm, the one before it having been taken.
func (o *open) next() {
	o.parts = nil
	o.run = nil
	o.chars = nil
	o.runFacts = facts{}
	o.runStates = 0
}

// choice is the choice: a choice of one arm is that arm.
func (o *open) choice() written {
	o.arms = appended(o.arms, o.arm())
	if len(o.arms) == 1 {
		return o.arms[0]
	}
	return eitherOfOf(o.arms)
}

// pattern is the whole text, as what it is written as. A choice is read with a stack of the
// choices open around it, so a group is a push and its closing bracket a pop, and nothing here
// recurses.
func (r *patternReader) pattern() written {
	var around []*open
	reading := &open{}
	for {
		if !r.done() && r.peek() != '|' && r.peek() != ')' {
			r.construct = r.at
			if r.peek() == '(' {
				r.opened()
				around = append(around, reading)
				reading = &open{}
			} else {
				r.atom(reading)
			}
			continue
		}
		if r.peek() == '|' {
			r.take()
			reading.arms = appended(reading.arms, reading.arm())
			reading.next()
			continue
		}
		choice := reading.choice()
		if len(around) == 0 {
			if !r.done() {
				// A bracket closing nothing, which is what is left when the reading of a choice
				// stops before the end.
				r.construct = r.at
				r.take()
				r.refuse(SomethingUnclosed)
			}
			return choice
		}
		r.expect(')')
		r.depth--
		reading = around[len(around)-1]
		around = around[:len(around)-1]
		r.construct = r.at
		reading.part(r.quantified(choice))
	}
}

// part puts w at the end of the arm being read. A group of nothing is nothing, and is left out so
// that one written pattern has one tree. An anchor is not one of those: where it stands decides
// what it comes to.
func (o *open) part(w written) {
	if w.kind == meantWritten && w.meaning.kind == nothingMeaning {
		return
	}
	if w.kind == meantWritten && w.meaning.kind == symbolsMeaning {
		if set := w.meaning.set(); set.size() == 1 {
			// One symbol, however written, as \x{3042} or [a] is, is one of the characters.
			o.character(set[0].first)
			return
		}
	}
	if w.facts.holds {
		o.flush()
		o.parts = appended(o.parts, w)
		return
	}
	// A part holding no anchor is a meantWritten as it is made, and joins the run as a sequence
	// part does ([inTurnOf]); every is held only while each part so far must take a symbol.
	o.settle()
	first := len(o.run) == 0
	o.run = appended(o.run, w.meaning)
	o.runFacts.may = o.runFacts.may || w.facts.may
	o.runFacts.must = o.runFacts.must || w.facts.must
	o.runFacts.every = (first || o.runFacts.every) && w.facts.must
	o.runStates = plusStates(o.runStates, w.states)
}

// character puts the one symbol c at the end of the arm being read, among the characters.
func (o *open) character(c rune) {
	first := len(o.run) == 0 && len(o.chars) == 0
	o.chars = appended(o.chars, runeRange{c, c})
	o.runFacts.may = true
	o.runFacts.must = true
	o.runFacts.every = first || o.runFacts.every
	o.runStates = plusStates(o.runStates, 1)
}

// settle puts the characters into the run as what they mean ([charactersMeaning]), which holds
// the list they were read into.
func (o *open) settle() {
	if len(o.chars) == 0 {
		return
	}
	o.run = appended(o.run, charactersMeaning(o.chars))
	o.chars = nil
}

// appended is s with v after it. A slice is made twice as large each time it fills, so that a
// sequence or a choice as long as the text is copied about once over as it grows: append doubles
// a short slice too, and grows a long one by a quarter.
func appended[T any](s []T, v T) []T {
	if len(s) == cap(s) && cap(s) >= 256 {
		grown := make([]T, len(s), 2*cap(s))
		copy(grown, s)
		s = grown
	}
	return append(s, v)
}

// opened reads a group's opening, plain or (?:, which are the two the grammar has.
func (r *patternReader) opened() {
	r.expect('(')
	if r.peek() == '?' {
		r.take()
		// (?: and nothing else. A lookaround and a named group have no spelling in the grammar,
		// and a flag group would change what a class means for the rest of the pattern.
		if r.peek() != ':' {
			r.take()
			r.refuse(AGroupTheGrammarDoesNotHave)
		}
		r.take()
	}
	r.depth++
	if r.depth > NestingDepth.Most() {
		// The group that went past it, from its bracket to where its reading stopped.
		r.beyond(NestingDepth, r.construct, r.at)
	}
}

// quantified is one with the count written after it, if any.
func (r *patternReader) quantified(one written) written {
	if !r.countHere() {
		return one
	}
	least, most := r.counted()
	return repeatedOf(one, least, most)
}

// countHere is whether a count is written here: whether what was read before is repeated. The
// one place that says what begins a count, and counted the one that reads it.
func (r *patternReader) countHere() bool {
	return !r.done() && countBegins[r.text[r.at]]
}

// countBegins is whether a count begins with the byte.
var countBegins = [256]bool{'?': true, '*': true, '+': true, '{': true}

// counted reads the count written here, where countHere says one is: the fewest and the most
// times.
func (r *patternReader) counted() (least, most int) {
	r.construct = r.at
	switch r.peek() {
	case '?':
		r.take()
		least, most = 0, 1
	case '*':
		r.take()
		least, most = 0, noCeiling
	case '+':
		r.take()
		least, most = 1, noCeiling
	default:
		r.expect('{')
		floor := r.count()
		ceiling := &floor
		if r.peek() == ',' {
			r.take()
			if r.peek() == '}' {
				ceiling = nil
			} else {
				c := r.count()
				ceiling = &c
			}
		}
		r.expect('}')
		// Compared as written, since either may be past what a count is held at.
		if ceiling != nil && ceiling.below(floor) {
			r.refuse(ACountThisCannotRead)
		}
		least, most = floor.held, noCeiling
		if ceiling != nil {
			most = ceiling.held
		}
	}
	// Reluctant says how a matcher walks and not which strings are accepted, so the marker is
	// read and left out. Possessive is not one of those: it takes what it can and gives none of it
	// back, so which strings it accepts follows from how a matcher walks.
	if r.peek() == '?' {
		r.take()
	} else if r.peek() == '+' {
		r.take()
		r.refuse(APossessiveRepetition)
	}
	return least, most
}

// atom reads one thing written other than a group, with the count written after it, and puts it at
// the end of the arm being read. The one place that tells a character written as itself from the
// rest of the grammar. Such a character with no count is one of the arm's characters and is made
// no meaning of its own; with a count, it is what the count repeats.
func (r *patternReader) atom(reading *open) {
	switch r.peek() {
	case '[':
		r.take()
		reading.part(r.quantified(symbolsWritten(r.characterClass())))
		return
	case '\\':
		r.take()
		reading.part(r.quantified(symbolsWritten(r.escaped())))
		return
	case '.':
		r.take()
		// Every symbol but the line terminators, written as a difference, so that a negated
		// class, which does not leave them out, is the same algebra with a different set taken
		// away.
		reading.part(r.quantified(symbolsWritten(dotSymbols)))
		return
	case '^', '$':
		end := r.peek() == '$'
		r.take()
		reading.part(r.quantified(anchorOf(end)))
		return
	case '{':
		// A brace that begins no count. Read as an ordinary character it would be a pattern
		// meaning one thing here and a count wherever a digit followed it.
		r.take()
		r.refuse(ACountThisCannotRead)
	case '*', '+', '?':
		r.take()
		r.refuse(SomethingUnclosed)
	case endOfText:
		r.refuse(SomethingUnclosed)
	}
	c := r.literal()
	if !r.countHere() {
		reading.character(c)
		return
	}
	reading.part(r.quantified(meant(literalMeaning(c))))
}

// symbolsWritten is held as what it means. One ASCII character, however written, as \| or [a] is,
// means what it means written as itself.
func symbolsWritten(held symbols) written {
	if len(held) == 1 && held[0].first == held[0].last && held[0].first < utf8.RuneSelf {
		return meant(asciiLiterals[held[0].first])
	}
	return meant(&meaning{kind: symbolsMeaning, ranges: held})
}

// characterClass is what is between [ and ], as the symbols it holds. The [ is already taken.
func (r *patternReader) characterClass() symbols {
	negated := r.peek() == '^'
	if negated {
		r.take()
	}
	// Gathered and put in order once, so that a class does not cost the square of its length.
	var members []runeRange
	first := true
	for !r.done() && (r.peek() != ']' || first) {
		first = false
		r.construct = r.at
		r.refuseClassOfClasses()
		members = append(members, r.classMember()...)
	}
	r.expect(']')
	held := normalized(members)
	if len(held) == 0 {
		r.refuse(SomethingUnclosed)
	}
	// The universe less what is written. A negated class does not leave out the line
	// terminators, which is why . is written as a difference of its own.
	if negated {
		return held.not()
	}
	return held
}

// refuseClassOfClasses refuses a [ or && here, as a class inside a class and as an intersection,
// wherever it stands in a class, an end of a run included.
func (r *patternReader) refuseClassOfClasses() {
	if r.peek() == '[' {
		r.take()
		r.refuse(AClassOfClasses)
	}
	if strings.HasPrefix(r.text[r.at:], "&&") {
		r.at += 2
		r.refuse(AClassOfClasses)
	}
}

// classMember is one member of a class: a symbol, a run of them, or a shorthand's whole set. A -
// makes a run only between two single symbols, each a character or an escape that stands for one.
// Anywhere else it is a symbol of its own: [a-\d] holds a, - and the digits.
func (r *patternReader) classMember() symbols {
	member := r.classAtom()
	if member.size() == 1 && r.peek() == '-' && r.at+1 < len(r.text) && r.text[r.at+1] != ']' {
		r.take()
		afterDash := r.at
		r.refuseClassOfClasses()
		upper := r.classAtom()
		if upper.size() != 1 {
			r.at = afterDash
			return append(append(symbols{}, member...), one('-')...)
		}
		if upper[0].first < member[0].first {
			r.refuse(ACountThisCannotRead)
		}
		return between(member[0].first, upper[0].first)
	}
	return member
}

func (r *patternReader) classAtom() symbols {
	if r.peek() == '\\' {
		r.take()
		return r.escaped()
	}
	return one(r.literal())
}

// escaped is what an escape stands for, as symbols. The backslash is already taken.
func (r *patternReader) escaped() symbols {
	if r.done() {
		r.refuse(AnEscapeThisDoesNotRead)
	}
	// The whole character after the backslash, so that one past the basic plane is classified as
	// the character it is.
	kind, _ := utf8.DecodeRuneInString(r.text[r.at:])
	switch kind {
	// The shorthands, as the language defines them: the digits are the ten ASCII ones, a word
	// character is ASCII with the underscore, and the whitespace is six characters.
	case 'd':
		r.take()
		return digitSymbols
	case 'D':
		r.take()
		return notDigitSymbols
	case 'w':
		r.take()
		return wordSymbols
	case 'W':
		r.take()
		return notWordSymbols
	case 's':
		r.take()
		return spaceSymbols
	case 'S':
		r.take()
		return notSpaceSymbols
	case 'n':
		r.take()
		return one('\n')
	case 't':
		r.take()
		return one('\t')
	case 'r':
		r.take()
		return one('\r')
	case 'f':
		r.take()
		return one('\f')
	case 'a':
		r.take()
		return one(0x07)
	case 'e':
		r.take()
		return one(0x1B)
	case '0':
		r.take()
		return one(r.octal())
	case 'x':
		r.take()
		return one(r.spelled(hexEscape(r.text, r.at)))
	case 'u':
		r.take()
		return one(r.spelled(unicodeEscape(r.text, r.at)))
	case 'p', 'P':
		r.refuseAfter(ACharacterProperty)
	case 'b', 'B', 'A', 'z', 'Z', 'G', 'R':
		r.refuseAfter(ABoundary)
	case 'Q', 'E':
		r.refuseAfter(AQuotation)
	case 'k', '1', '2', '3', '4', '5', '6', '7', '8', '9':
		r.refuseAfter(ABackReference)
	}
	// An escaped literal: \. \+ \\ \-. A letter or a decimal digit with no meaning is refused
	// rather than read as itself: read as itself, one given a meaning later would change which
	// strings an old pattern accepts.
	if lettersAndDigits.has(kind) {
		r.refuseAfter(AnEscapeThisDoesNotRead)
	}
	return one(r.literal())
}

// refuseAfter refuses the escape whose kind is the character here, quoting it with that character
// whole.
func (r *patternReader) refuseAfter(why PatternRefusal) {
	r.take()
	r.refuse(why)
}

// spelled is the symbol a \x or \u escape spells, the reading moved past it. A \u pair is the one
// character it encodes: read as two symbols, 𐀀 would name the two halves and not
// U+10000.
func (r *patternReader) spelled(symbol rune, end int, ok bool) rune {
	if !ok {
		r.refuse(AnEscapeThisDoesNotRead)
	}
	r.at = end
	return r.symbol(symbol)
}

// symbol is a code point as a symbol, the reading already moved past what wrote it. Every
// character a pattern names comes through here, however it was written. A surrogate is no symbol,
// since no text holds one, and is refused.
func (r *patternReader) symbol(codePoint rune) rune {
	if isSurrogate(codePoint) {
		r.refuse(ACharacterNoStringHolds)
	}
	return codePoint
}

// octal reads \0n, \0nn or \0mnn: up to three octal digits after the zero, up to 377.
func (r *patternReader) octal() rune {
	var value rune
	digits := 0
	for digits < 3 && r.peek() >= '0' && r.peek() <= '7' {
		value = value*8 + rune(r.take()-'0')
		digits++
	}
	if digits == 0 || value > 0xFF {
		r.refuse(AnEscapeThisDoesNotRead)
	}
	return value
}

// literal is the symbol written here, a whole scalar value. Bytes that are not UTF-8 are no
// character.
func (r *patternReader) literal() rune {
	if r.done() {
		r.refuse(SomethingUnclosed)
	}
	written, size := utf8.DecodeRuneInString(r.text[r.at:])
	r.at += size
	if written == utf8.RuneError && size == 1 {
		r.refuse(ACharacterNoStringHolds)
	}
	return r.symbol(written)
}

// repetitionCount is a count as written: its digits without leading zeros, and the value it is
// held at, which is one past the limit where it is past that.
type repetitionCount struct {
	digits string
	held   int
}

// below is whether this is a smaller number than other, compared as written.
func (c repetitionCount) below(other repetitionCount) bool {
	if len(c.digits) != len(other.digits) {
		return len(c.digits) < len(other.digits)
	}
	return c.digits < other.digits
}

// count reads a repetition's count. Every digit is read, and a count past the limit is noted and
// held at one more than it, which is all that is asked of its value; the digits are kept so that a
// floor and a ceiling are compared as they are written.
func (r *patternReader) count() repetitionCount {
	from := r.at
	most := RepetitionCount.Most()
	value := 0
	for r.peek() >= '0' && r.peek() <= '9' {
		value = min(most+1, value*10+int(r.take()-'0'))
	}
	if r.at == from {
		r.refuse(ACountThisCannotRead)
	}
	if value > most {
		r.beyond(RepetitionCount, from, r.at)
	}
	digits := strings.TrimLeft(r.text[from:r.at], "0")
	if digits == "" {
		digits = "0"
	}
	return repetitionCount{digits: digits, held: value}
}

// beyond notes the first limit met in the text, which is the answer if the text turns out to be
// a pattern.
func (r *patternReader) beyond(limit PatternLimit, from, to int) {
	if r.past == nil {
		r.past = &PatternBeyond{Limit: limit, From: from, Construct: r.text[from:to]}
	}
}

func (r *patternReader) done() bool { return r.at >= len(r.text) }

// peek is the byte here, or endOfText past the last one. Read as a byte rather than as a symbol,
// because what the grammar branches on is punctuation, all of it ASCII.
func (r *patternReader) peek() int {
	if r.done() {
		return endOfText
	}
	return int(r.text[r.at])
}

// take moves past the character here, whole, and answers its first byte.
func (r *patternReader) take() byte {
	if r.done() {
		r.refuse(SomethingUnclosed)
	}
	first := r.text[r.at]
	_, size := utf8.DecodeRuneInString(r.text[r.at:])
	r.at += size
	return first
}

func (r *patternReader) expect(c byte) {
	if r.peek() != int(c) {
		// What is missing is a closing, and where it was looked for is what an author is sent to.
		r.construct = r.at
		r.refuse(SomethingUnclosed)
	}
	r.take()
}

// refuse refuses the construct being read, quoting it from where it began to where the reading
// stopped.
func (r *patternReader) refuse(why PatternRefusal) {
	panic(refusal{why: why, from: r.construct, to: r.at})
}

// unicodeEscape is what \u spells, read from at, just past the u: four hex digits, and where they
// are a high surrogate followed by a \u escape of a low one, the one character the two encode.
// Not ok where there are not four hex digits. A high escape with no low one after it spells the
// high surrogate, which no text holds and the reader refuses.
func unicodeEscape(text string, at int) (symbol rune, end int, ok bool) {
	first, ok := fixedHex(text, at, 4)
	if !ok {
		return 0, 0, false
	}
	next := at + 4
	if first >= 0xD800 && first <= 0xDBFF && strings.HasPrefix(text[next:], `\u`) {
		if second, ok := fixedHex(text, next+2, 4); ok && second >= 0xDC00 && second <= 0xDFFF {
			return 0x10000 + (first-0xD800)<<10 + (second - 0xDC00), next + 6, true
		}
	}
	return first, next, true
}

// hexEscape is what \x spells, read from at, just past the x: two hex digits, or any number of
// them in braces up to U+10FFFF. Not ok where it is neither.
func hexEscape(text string, at int) (symbol rune, end int, ok bool) {
	if !strings.HasPrefix(text[at:], "{") {
		value, ok := fixedHex(text, at, 2)
		return value, at + 2, ok
	}
	var value rune
	digits := 0
	here := at + 1
	for here < len(text) && text[here] != '}' {
		digit := hexDigit(text[here])
		if digit < 0 {
			return 0, 0, false
		}
		value = value*16 + digit
		digits++
		if value > lastSymbol {
			return 0, 0, false
		}
		here++
	}
	if here >= len(text) || digits == 0 {
		return 0, 0, false
	}
	return value, here + 1, true
}

// fixedHex is the digits hex digits at at as a number, and not ok where they are not there.
func fixedHex(text string, at, digits int) (rune, bool) {
	if at+digits > len(text) {
		return 0, false
	}
	var value rune
	for i := at; i < at+digits; i++ {
		digit := hexDigit(text[i])
		if digit < 0 {
			return 0, false
		}
		value = value*16 + digit
	}
	return value, true
}

// hexDigit is the value of an ASCII hex digit, in either case, or -1 where c is none. A
// fullwidth digit is no digit to the language.
func hexDigit(c byte) rune {
	switch {
	case c >= '0' && c <= '9':
		return rune(c - '0')
	case c >= 'A' && c <= 'F':
		return rune(c-'A') + 10
	case c >= 'a' && c <= 'f':
		return rune(c-'a') + 10
	}
	return -1
}
