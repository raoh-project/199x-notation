// The reader of the pattern language, and the only one: what it hands on is the meaning, and nothing
// after it reads the text again.

import { placeAnchors } from "./pattern_anchors.ts";
import {
  anchorOf,
  charactersMeaning,
  eitherOfOf,
  type Facts,
  inTurnOf,
  type Meaning,
  meant,
  NO_CEILING,
  NO_FACTS,
  NOTHING,
  repeatedOf,
  symbolsMeaning,
  type Written,
} from "./pattern_meaning.ts";
import { MOST_STATES, plusStates, wholeStates } from "./pattern_states.ts";
import {
  between,
  DIGITS,
  DOT,
  isLetterOrDigit,
  isSurrogate,
  LAST_SYMBOL,
  normalized,
  not,
  NOT_DIGITS,
  NOT_SPACE,
  NOT_WORD,
  one,
  size,
  SPACE,
  type Symbols,
  WORD,
} from "./pattern_symbols.ts";

/** Which limit on a pattern it is past. */
export type PatternLimit =
  /** A count of a repetition, written in {n}, {n,} or {n,m}. */
  | "repetitionCount"
  /** Groups one inside another. */
  | "nestingDepth"
  /** The states of the pattern with its repetitions written out, counted from the text. */
  | "machineStates";

/**
 * The limits on a pattern every implementation holds to, each the same number everywhere: the
 * greatest count, depth or number of states within each. They bound what running a pattern costs,
 * and are stated of the text so that no implementation's way of running one decides which patterns
 * it takes.
 */
export const PATTERN_LIMITS: Readonly<Record<PatternLimit, number>> = {
  repetitionCount: 134_217_727,
  nestingDepth: 200,
  machineStates: MOST_STATES,
};

/**
 * What makes text no pattern of the language, told apart by what an author wrote. The first three
 * are text that is no pattern at all. The rest are text that would be a pattern in some other
 * language and is not one in this, each for a reason of its own.
 */
export type PatternRefusal =
  /**
   * A bracket, brace or parenthesis with nothing closing it, a class with nothing in it, or a
   * repetition with nothing before it to repeat.
   */
  | "somethingUnclosed"
  /**
   * A repetition whose count is no count: one with no digits, a ceiling below its floor, or a run
   * whose end comes before its start. A count past the limit is a count, and is beyond it.
   */
  | "aCountThisCannotRead"
  /** An escape with no meaning, or one with nothing after it. */
  | "anEscapeThisDoesNotRead"
  /**
   * A character no text holds: half of a surrogate pair, written by its number, \uD800 on its own or
   * \x{DC00}, or held in the text of the pattern with no other half beside it.
   */
  | "aCharacterNoStringHolds"
  /** A group beginning (? other than (?: — a lookahead, a lookbehind, a named group, a flag group. */
  | "aGroupTheGrammarDoesNotHave"
  /** A reference back to what another part of the pattern matched, which can denote a set no regular language is. */
  | "aBackReference"
  /** A property of a character, \p{Alpha} or \P{...}: the language names symbols by their numbers. */
  | "aCharacterProperty"
  /** \b, \B, \A, \z, \Z, \G or \R: the grammar has ^ and $ for the ends and nothing else between characters. */
  | "aBoundary"
  /** \Q ... \E, which turns off the reading of what is inside it. */
  | "aQuotation"
  /** A class inside a class, or classes joined by &&. */
  | "aClassOfClasses"
  /**
   * ++, *+ and the rest: a repetition that gives nothing back, whose strings follow from how a
   * matcher walks, which the language does not describe.
   */
  | "aPossessiveRepetition"
  /**
   * An anchor whose answer is not a property of the pattern, as in (a|)^b, where which strings are
   * accepted is settled by which arm a string took.
   */
  | "anAnchorThisCannotPlace";

/** Text that is no pattern of the language, and what stopped the reading. */
export interface PatternRefused {
  /** Which kind of thing it is. */
  readonly why: PatternRefusal;
  /** Where in the text the construct that stopped the reading begins, in UTF-16 code units. */
  readonly from: number;
  /** The construct as written, empty where the text ended before a construct it had begun was whole. */
  readonly construct: string;
}

/**
 * A pattern of the language written past one of the limits every implementation holds to.
 *
 * Not a refusal: the language has no count, depth or size past which a pattern stops being one.
 * What is past a limit is what no implementation is asked to run. Answered only of text read to its
 * end and found to be a pattern, its anchors placed: text that is no pattern is refused, whatever
 * limit it also went past.
 */
export interface PatternBeyond {
  /** Which limit it is past. */
  readonly limit: PatternLimit;
  /**
   * Where in the text the construct that is past it begins, in UTF-16 code units; nought for
   * `machineStates`, which is a fact about the whole pattern.
   */
  readonly from: number;
  /** The construct as written: the count, the group opened past the depth, or the whole pattern. */
  readonly construct: string;
}

/** What reading text as a pattern came to: its meaning, a refusal, or the limit it is past. */
export type Reading =
  | { readonly meaning: Meaning }
  | { readonly refused: PatternRefused }
  | { readonly beyond: PatternBeyond };

/** What the reader throws where the text is no pattern, carried to the one place that answers. */
class Refusal {
  readonly why: PatternRefusal;
  readonly from: number;
  readonly to: number;

  constructor(why: PatternRefusal, from: number, to: number) {
    this.why = why;
    this.from = from;
    this.to = to;
  }
}

/** What `peek` answers past the last code unit, outside every value a code unit has. */
const END_OF_TEXT = -1;

/** What `text` means as a pattern, or what makes it no pattern, or which limit it is past. */
export function readText(text: string): Reading {
  const r = new Reader(text);
  let w: Written;
  try {
    w = r.pattern();
  } catch (thrown) {
    if (!(thrown instanceof Refusal)) {
      throw thrown;
    }
    const to = Math.min(text.length, Math.max(thrown.to, thrown.from));
    return { refused: { why: thrown.why, from: thrown.from, construct: text.slice(thrown.from, to) } };
  }
  // Every anchor has to come to something, and what it comes to is settled by where it stands,
  // which is known now that the whole of the pattern is.
  const meaning = placeAnchors(w);
  if (meaning === undefined) {
    return { refused: { why: "anAnchorThisCannotPlace", from: 0, construct: text } };
  }
  // The text is a pattern. Whether it is one every implementation takes is asked now.
  if (r.past !== undefined) {
    return { beyond: r.past };
  }
  // Counted on what was written, where an anchor is one state whatever it came to, so the count is
  // never below the states of the machine the meaning builds.
  if (wholeStates(w.states) > PATTERN_LIMITS.machineStates) {
    return { beyond: { limit: "machineStates", from: 0, construct: text } };
  }
  return { meaning };
}

/**
 * A choice being read, in a group or at the top: the arms read so far, and the parts of the one
 * being read.
 *
 * The parts read since the last that holds an anchor hold none, and are held in `run` as what they
 * mean, with what they are together. Characters of one symbol each read one after another are held
 * in `chars`, and go into `run` as one meaning when a part of another kind comes (settle). Where a
 * part holding an anchor comes, the run goes into `parts` as one (flush), so the parts of a sequence
 * are the ones holding an anchor and the runs between them.
 */
class Open {
  arms: Written[] = [];
  parts: Written[] = [];
  run: Meaning[] = [];
  chars: number[] = [];
  /**
   * What the run is as a sequence, and `every` whether each of its parts must take a symbol;
   * `runStates` the states they come to together.
   */
  runFacts: Facts = NO_FACTS;
  runStates = 0;

  /**
   * The arm being read: an arm of one part is that part, and an arm of none is nothing. One with no
   * part holding an anchor is what its run means.
   */
  arm(): Written {
    this.settle();
    if (this.parts.length === 0) {
      switch (this.run.length) {
        case 0:
          return meant(NOTHING);
        case 1:
          return this.runPart();
        default:
          return { kind: "meant", meaning: { kind: "inTurn", parts: this.run }, facts: this.runFacts, states: this.runStates };
      }
    }
    this.flush();
    return this.parts.length === 1 ? this.parts[0]! : inTurnOf(this.parts);
  }

  /** The one part the run holds. */
  private runPart(): Written {
    return { kind: "meant", meaning: this.run[0]!, facts: this.runFacts, states: this.runStates };
  }

  /** Puts the run into the parts, as the one part it is or as a run, and starts another. */
  private flush(): void {
    this.settle();
    switch (this.run.length) {
      case 0:
        return;
      case 1:
        this.parts.push(this.runPart());
        break;
      default:
        this.parts.push({ kind: "run", meaning: { kind: "inTurn", parts: this.run }, facts: this.runFacts, states: this.runStates });
    }
    this.run = [];
    this.runFacts = NO_FACTS;
    this.runStates = 0;
  }

  /** Starts the next arm, the one before it having been taken. */
  next(): void {
    this.parts = [];
    this.run = [];
    this.chars = [];
    this.runFacts = NO_FACTS;
    this.runStates = 0;
  }

  /** The choice: a choice of one arm is that arm. */
  choice(): Written {
    this.arms.push(this.arm());
    return this.arms.length === 1 ? this.arms[0]! : eitherOfOf(this.arms);
  }

  /**
   * Puts `w` at the end of the arm being read. A group of nothing is nothing, and is left out so that
   * one written pattern has one tree. An anchor is not one of those: where it stands decides what it
   * comes to.
   */
  part(w: Written): void {
    if (w.kind === "meant" && w.meaning.kind === "nothing") {
      return;
    }
    if (w.kind === "meant" && w.meaning.kind === "symbols" && size(w.meaning.set) === 1) {
      // One symbol, however written, as \x{3042} or [a] is, is one of the characters.
      this.character(w.meaning.set[0]!);
      return;
    }
    if (w.facts.holds) {
      this.flush();
      this.parts.push(w);
      return;
    }
    // A part holding no anchor is meant as it is made, and joins the run as a sequence part does;
    // every is held only while each part so far must take a symbol.
    this.settle();
    const first = this.run.length === 0;
    this.run.push(w.kind === "meant" ? w.meaning : unreachableKind(w));
    this.runFacts = {
      may: this.runFacts.may || w.facts.may,
      must: this.runFacts.must || w.facts.must,
      holds: false,
      every: (first || this.runFacts.every) && w.facts.must,
    };
    this.runStates = plusStates(this.runStates, w.states);
  }

  /** Puts the one symbol `cp` at the end of the arm being read, among the characters. */
  character(cp: number): void {
    const first = this.run.length === 0 && this.chars.length === 0;
    this.chars.push(cp);
    this.runFacts = { may: true, must: true, holds: false, every: first || this.runFacts.every };
    this.runStates = plusStates(this.runStates, 1);
  }

  /** Puts the characters into the run as what they mean. */
  private settle(): void {
    if (this.chars.length === 0) {
      return;
    }
    this.run.push(charactersMeaning(this.chars));
    this.chars = [];
  }
}

function unreachableKind(w: Written): never {
  throw new Error(`a part holding no anchor is meant, and this is ${w.kind}`);
}

class Reader {
  private readonly text: string;
  private at = 0;
  private depth = 0;
  /** Where the construct being read begins, which is what a refusal quotes. */
  private construct = 0;
  /** The first limit met in the text, or `undefined` while none has been. */
  past: PatternBeyond | undefined;

  constructor(text: string) {
    this.text = text;
  }

  /**
   * The whole text, as what it is written as. A choice is read with a stack of the choices open
   * around it, so a group is a push and its closing bracket a pop, and nothing here recurses.
   */
  pattern(): Written {
    const around: Open[] = [];
    let reading = new Open();
    for (;;) {
      if (!this.done() && this.peek() !== PIPE && this.peek() !== CLOSE) {
        this.construct = this.at;
        if (this.peek() === OPEN) {
          this.opened();
          around.push(reading);
          reading = new Open();
        } else {
          this.atom(reading);
        }
        continue;
      }
      if (this.peek() === PIPE) {
        this.take();
        reading.arms.push(reading.arm());
        reading.next();
        continue;
      }
      const choice = reading.choice();
      if (around.length === 0) {
        if (!this.done()) {
          // A bracket closing nothing, which is what is left when the reading of a choice stops
          // before the end.
          this.construct = this.at;
          this.take();
          this.refuse("somethingUnclosed");
        }
        return choice;
      }
      this.expect(CLOSE);
      this.depth--;
      reading = around.pop()!;
      this.construct = this.at;
      reading.part(this.quantified(choice));
    }
  }

  /** Reads a group's opening, plain or (?:, which are the two the grammar has. */
  private opened(): void {
    this.expect(OPEN);
    if (this.peek() === QUESTION) {
      this.take();
      // (?: and nothing else. A lookaround and a named group have no spelling in the grammar, and a
      // flag group would change what a class means for the rest of the pattern.
      if (this.peek() !== COLON) {
        this.take();
        this.refuse("aGroupTheGrammarDoesNotHave");
      }
      this.take();
    }
    this.depth++;
    if (this.depth > PATTERN_LIMITS.nestingDepth) {
      // The group that went past it, from its bracket to where its reading stopped.
      this.beyond("nestingDepth", this.construct, this.at);
    }
  }

  /** `one` with the count written after it, if any. */
  private quantified(one: Written): Written {
    if (!this.countHere()) {
      return one;
    }
    const [least, most] = this.counted();
    return repeatedOf(one, least, most);
  }

  /**
   * Whether a count is written here: whether what was read before is repeated. The one place that
   * says what begins a count, and `counted` the one that reads it.
   */
  private countHere(): boolean {
    const c = this.peek();
    return c === QUESTION || c === STAR || c === PLUS || c === OPEN_BRACE;
  }

  /** The count written here, where `countHere` says one is: the fewest and the most times. */
  private counted(): [number, number] {
    this.construct = this.at;
    let least: number;
    let most: number;
    switch (this.peek()) {
      case QUESTION:
        this.take();
        [least, most] = [0, 1];
        break;
      case STAR:
        this.take();
        [least, most] = [0, NO_CEILING];
        break;
      case PLUS:
        this.take();
        [least, most] = [1, NO_CEILING];
        break;
      default: {
        this.expect(OPEN_BRACE);
        const floor = this.count();
        let ceiling: RepetitionCount | undefined = floor;
        if (this.peek() === COMMA) {
          this.take();
          ceiling = this.peek() === CLOSE_BRACE ? undefined : this.count();
        }
        this.expect(CLOSE_BRACE);
        // Compared as written, since either may be past what a count is held at.
        if (ceiling !== undefined && below(ceiling, floor)) {
          this.refuse("aCountThisCannotRead");
        }
        least = floor.held;
        most = ceiling === undefined ? NO_CEILING : ceiling.held;
      }
    }
    // Reluctant says how a matcher walks and not which strings are accepted, so the marker is read
    // and left out. Possessive is not one of those: it takes what it can and gives none of it back,
    // so which strings it accepts follows from how a matcher walks.
    if (this.peek() === QUESTION) {
      this.take();
    } else if (this.peek() === PLUS) {
      this.take();
      this.refuse("aPossessiveRepetition");
    }
    return [least, most];
  }

  /**
   * Reads one thing written other than a group, with the count written after it, and puts it at the
   * end of the arm being read. The one place that tells a character written as itself from the rest
   * of the grammar. Such a character with no count is one of the arm's characters and is made no
   * meaning of its own; with a count, it is what the count repeats.
   */
  private atom(reading: Open): void {
    switch (this.peek()) {
      case OPEN_BRACKET:
        this.take();
        reading.part(this.quantified(meant(symbolsMeaning(this.characterClass()))));
        return;
      case BACKSLASH:
        this.take();
        reading.part(this.quantified(meant(symbolsMeaning(this.escaped()))));
        return;
      case DOT_CHARACTER:
        this.take();
        // Every symbol but the line terminators, written as a difference, so that a negated class,
        // which does not leave them out, is the same algebra with a different set taken away.
        reading.part(this.quantified(meant(symbolsMeaning(DOT))));
        return;
      case CARET:
      case DOLLAR: {
        const end = this.peek() === DOLLAR;
        this.take();
        reading.part(this.quantified(anchorOf(end)));
        return;
      }
      case OPEN_BRACE:
        // A brace that begins no count. Read as an ordinary character it would be a pattern meaning
        // one thing here and a count wherever a digit followed it.
        this.take();
        this.refuse("aCountThisCannotRead");
        break;
      case STAR:
      case PLUS:
      case QUESTION:
        this.take();
        this.refuse("somethingUnclosed");
        break;
      case END_OF_TEXT:
        this.refuse("somethingUnclosed");
        break;
    }
    const c = this.literal();
    if (!this.countHere()) {
      reading.character(c);
      return;
    }
    reading.part(this.quantified(meant(symbolsMeaning(one(c)))));
  }

  /** What is between [ and ], as the symbols it holds. The [ is already taken. */
  private characterClass(): Symbols {
    const negated = this.peek() === CARET;
    if (negated) {
      this.take();
    }
    // Gathered and put in order once, so that a class does not cost the square of its length.
    const members: number[] = [];
    let first = true;
    while (!this.done() && (this.peek() !== CLOSE_BRACKET || first)) {
      first = false;
      this.construct = this.at;
      this.refuseClassOfClasses();
      for (const bound of this.classMember()) {
        members.push(bound);
      }
    }
    this.expect(CLOSE_BRACKET);
    const held = normalized(members);
    if (held.length === 0) {
      this.refuse("somethingUnclosed");
    }
    // The universe less what is written. A negated class does not leave out the line terminators,
    // which is why . is written as a difference of its own.
    return negated ? not(held) : held;
  }

  /**
   * A [ or && here is refused, as a class inside a class and as an intersection, wherever it
   * stands in a class, an end of a run included.
   */
  private refuseClassOfClasses(): void {
    if (this.peek() === OPEN_BRACKET) {
      this.take();
      this.refuse("aClassOfClasses");
    }
    if (this.text.startsWith("&&", this.at)) {
      this.at += 2;
      this.refuse("aClassOfClasses");
    }
  }

  /**
   * One member of a class: a symbol, a run of them, or a shorthand's whole set. A - makes a run
   * only between two single symbols, each a character or an escape that stands for one. Anywhere
   * else it is a symbol of its own: [a-\d] holds a, - and the digits.
   */
  private classMember(): Symbols {
    const member = this.classAtom();
    if (size(member) === 1 && this.peek() === HYPHEN && this.at + 1 < this.text.length
      && this.text.charCodeAt(this.at + 1) !== CLOSE_BRACKET) {
      this.take();
      const afterDash = this.at;
      this.refuseClassOfClasses();
      const upper = this.classAtom();
      if (size(upper) !== 1) {
        this.at = afterDash;
        return normalized([...member, HYPHEN, HYPHEN]);
      }
      if (upper[0]! < member[0]!) {
        this.refuse("aCountThisCannotRead");
      }
      return between(member[0]!, upper[0]!);
    }
    return member;
  }

  private classAtom(): Symbols {
    if (this.peek() === BACKSLASH) {
      this.take();
      return this.escaped();
    }
    return one(this.literal());
  }

  /** What an escape stands for, as symbols. The backslash is already taken. */
  private escaped(): Symbols {
    if (this.done()) {
      this.refuse("anEscapeThisDoesNotRead");
    }
    // The whole character after the backslash, so that one past the basic plane is classified as
    // the character it is.
    const kind = this.text.codePointAt(this.at)!;
    switch (kind) {
      // The shorthands, as the language defines them: the digits are the ten ASCII ones, a word
      // character is ASCII with the underscore, and the whitespace is six characters.
      case 0x64: // d
        this.take();
        return DIGITS;
      case 0x44: // D
        this.take();
        return NOT_DIGITS;
      case 0x77: // w
        this.take();
        return WORD;
      case 0x57: // W
        this.take();
        return NOT_WORD;
      case 0x73: // s
        this.take();
        return SPACE;
      case 0x53: // S
        this.take();
        return NOT_SPACE;
      case 0x6E: // n
        this.take();
        return one(0x0A);
      case 0x74: // t
        this.take();
        return one(0x09);
      case 0x72: // r
        this.take();
        return one(0x0D);
      case 0x66: // f
        this.take();
        return one(0x0C);
      case 0x61: // a
        this.take();
        return one(0x07);
      case 0x65: // e
        this.take();
        return one(0x1B);
      case 0x30: // 0
        this.take();
        return one(this.octal());
      case 0x78: // x
        this.take();
        return one(this.spelled(hexEscape(this.text, this.at)));
      case 0x75: // u
        this.take();
        return one(this.spelled(unicodeEscape(this.text, this.at)));
      case 0x70: // p
      case 0x50: // P
        this.refuseAfter("aCharacterProperty");
        break;
      case 0x62: // b
      case 0x42: // B
      case 0x41: // A
      case 0x7A: // z
      case 0x5A: // Z
      case 0x47: // G
      case 0x52: // R
        this.refuseAfter("aBoundary");
        break;
      case 0x51: // Q
      case 0x45: // E
        this.refuseAfter("aQuotation");
        break;
      case 0x6B: // k
      case 0x31:
      case 0x32:
      case 0x33:
      case 0x34:
      case 0x35:
      case 0x36:
      case 0x37:
      case 0x38:
      case 0x39:
        this.refuseAfter("aBackReference");
        break;
    }
    // An escaped literal: \. \+ \\ \-. A letter or a decimal digit with no meaning is refused rather
    // than read as itself: read as itself, one given a meaning later would change which strings an
    // old pattern accepts.
    if (isLetterOrDigit(kind)) {
      this.refuseAfter("anEscapeThisDoesNotRead");
    }
    return one(this.literal());
  }

  /** Refuses the escape whose kind is the character here, quoting it with that character whole. */
  private refuseAfter(why: PatternRefusal): never {
    this.take();
    this.refuse(why);
  }

  /**
   * The symbol a \x or \u escape spells, the reading moved past it. A \u pair is the one character
   * it encodes: read as two symbols, 𐀀 would name the two halves and not U+10000.
   */
  private spelled(escape: Escape | undefined): number {
    if (escape === undefined) {
      this.refuse("anEscapeThisDoesNotRead");
    }
    this.at = escape.end;
    return this.symbol(escape.symbol);
  }

  /**
   * A code point as a symbol, the reading already moved past what wrote it. Every character a
   * pattern names comes through here, however it was written. A surrogate is no symbol, since no
   * text holds one, and is refused.
   */
  private symbol(cp: number): number {
    if (isSurrogate(cp)) {
      this.refuse("aCharacterNoStringHolds");
    }
    return cp;
  }

  /** Reads \0n, \0nn or \0mnn: up to three octal digits after the zero, up to 377. */
  private octal(): number {
    let value = 0;
    let digits = 0;
    while (digits < 3 && this.peek() >= 0x30 && this.peek() <= 0x37) {
      value = value * 8 + (this.take() - 0x30);
      digits++;
    }
    if (digits === 0 || value > 0xFF) {
      this.refuse("anEscapeThisDoesNotRead");
    }
    return value;
  }

  /**
   * The symbol written here, a whole scalar value. Half of a surrogate pair with no other half
   * beside it is no character.
   */
  private literal(): number {
    if (this.done()) {
      this.refuse("somethingUnclosed");
    }
    const written = this.text.codePointAt(this.at)!;
    this.at += written > 0xFFFF ? 2 : 1;
    return this.symbol(written);
  }

  /**
   * Reads a repetition's count. Every digit is read, and a count past the limit is noted and held
   * at one more than it, which is all that is asked of its value; the digits are kept so that a
   * floor and a ceiling are compared as they are written.
   */
  private count(): RepetitionCount {
    const from = this.at;
    const most = PATTERN_LIMITS.repetitionCount;
    let value = 0;
    while (this.peek() >= 0x30 && this.peek() <= 0x39) {
      value = Math.min(most + 1, value * 10 + (this.take() - 0x30));
    }
    if (this.at === from) {
      this.refuse("aCountThisCannotRead");
    }
    if (value > most) {
      this.beyond("repetitionCount", from, this.at);
    }
    const digits = this.text.slice(from, this.at).replace(/^0+/, "") || "0";
    return { digits, held: value };
  }

  /** Notes the first limit met in the text, which is the answer if the text turns out to be a pattern. */
  private beyond(limit: PatternLimit, from: number, to: number): void {
    this.past ??= { limit, from, construct: this.text.slice(from, to) };
  }

  private done(): boolean {
    return this.at >= this.text.length;
  }

  /**
   * The code unit here, or `END_OF_TEXT` past the last one. Read as a unit rather than as a symbol,
   * because what the grammar branches on is punctuation, all of it ASCII.
   */
  private peek(): number {
    return this.done() ? END_OF_TEXT : this.text.charCodeAt(this.at);
  }

  /** Moves past the character here, whole, and answers its first code unit. */
  private take(): number {
    if (this.done()) {
      this.refuse("somethingUnclosed");
    }
    const first = this.text.charCodeAt(this.at);
    this.at += this.text.codePointAt(this.at)! > 0xFFFF ? 2 : 1;
    return first;
  }

  private expect(c: number): void {
    if (this.peek() !== c) {
      // What is missing is a closing, and where it was looked for is what an author is sent to.
      this.construct = this.at;
      this.refuse("somethingUnclosed");
    }
    this.take();
  }

  /** Refuses the construct being read, quoting it from where it began to where the reading stopped. */
  private refuse(why: PatternRefusal): never {
    throw new Refusal(why, this.construct, this.at);
  }
}

/**
 * A count as written: its digits without leading zeros, and the value it is held at, which is one
 * past the limit where it is past that.
 */
interface RepetitionCount {
  readonly digits: string;
  readonly held: number;
}

/** Whether `c` is a smaller number than `other`, compared as written. */
function below(c: RepetitionCount, other: RepetitionCount): boolean {
  if (c.digits.length !== other.digits.length) {
    return c.digits.length < other.digits.length;
  }
  return c.digits < other.digits;
}

/** What a \x or \u escape spells, and where the text after it begins. */
interface Escape {
  readonly symbol: number;
  readonly end: number;
}

/**
 * What \u spells, read from `at`, just past the u: four hex digits, and where they are a high
 * surrogate followed by a \u escape of a low one, the one character the two encode. `undefined`
 * where there are not four hex digits. A high escape with no low one after it spells the high
 * surrogate, which no text holds and the reader refuses.
 */
function unicodeEscape(text: string, at: number): Escape | undefined {
  const first = fixedHex(text, at, 4);
  if (first === undefined) {
    return undefined;
  }
  const next = at + 4;
  if (first >= 0xD800 && first <= 0xDBFF && text.startsWith("\\u", next)) {
    const second = fixedHex(text, next + 2, 4);
    if (second !== undefined && second >= 0xDC00 && second <= 0xDFFF) {
      return { symbol: 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00), end: next + 6 };
    }
  }
  return { symbol: first, end: next };
}

/**
 * What \x spells, read from `at`, just past the x: two hex digits, or any number of them in braces up
 * to U+10FFFF. `undefined` where it is neither.
 */
function hexEscape(text: string, at: number): Escape | undefined {
  if (!text.startsWith("{", at)) {
    const value = fixedHex(text, at, 2);
    return value === undefined ? undefined : { symbol: value, end: at + 2 };
  }
  let value = 0;
  let digits = 0;
  let here = at + 1;
  while (here < text.length && text.charCodeAt(here) !== CLOSE_BRACE) {
    const digit = hexDigit(text.charCodeAt(here));
    if (digit < 0) {
      return undefined;
    }
    value = value * 16 + digit;
    digits++;
    if (value > LAST_SYMBOL) {
      return undefined;
    }
    here++;
  }
  if (here >= text.length || digits === 0) {
    return undefined;
  }
  return { symbol: value, end: here + 1 };
}

/** The `digits` hex digits at `at` as a number, and `undefined` where they are not there. */
function fixedHex(text: string, at: number, digits: number): number | undefined {
  if (at + digits > text.length) {
    return undefined;
  }
  let value = 0;
  for (let i = at; i < at + digits; i++) {
    const digit = hexDigit(text.charCodeAt(i));
    if (digit < 0) {
      return undefined;
    }
    value = value * 16 + digit;
  }
  return value;
}

/** The value of an ASCII hex digit, in either case, or -1 where `c` is none. A fullwidth digit is no digit to the language. */
function hexDigit(c: number): number {
  if (c >= 0x30 && c <= 0x39) {
    return c - 0x30;
  }
  if (c >= 0x41 && c <= 0x46) {
    return c - 0x41 + 10;
  }
  if (c >= 0x61 && c <= 0x66) {
    return c - 0x61 + 10;
  }
  return -1;
}

const PIPE = 0x7C;
const OPEN = 0x28;
const CLOSE = 0x29;
const QUESTION = 0x3F;
const STAR = 0x2A;
const PLUS = 0x2B;
const COLON = 0x3A;
const COMMA = 0x2C;
const OPEN_BRACE = 0x7B;
const CLOSE_BRACE = 0x7D;
const OPEN_BRACKET = 0x5B;
const CLOSE_BRACKET = 0x5D;
const BACKSLASH = 0x5C;
const DOT_CHARACTER = 0x2E;
const CARET = 0x5E;
const DOLLAR = 0x24;
const HYPHEN = 0x2D;
