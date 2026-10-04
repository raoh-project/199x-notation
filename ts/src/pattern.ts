// The pattern language: reading a pattern, the limits every implementation holds a pattern to, and
// the strings a pattern accepts.

import type { Meaning } from "./pattern_meaning.ts";
import { build } from "./pattern_machine.ts";
import { type PatternBeyond, type PatternRefused, readText } from "./pattern_read.ts";
import { Walk } from "./pattern_walk.ts";

export {
  PATTERN_LIMITS,
  type PatternBeyond,
  type PatternLimit,
  type PatternRefusal,
  type PatternRefused,
} from "./pattern_read.ts";

/**
 * What came of reading a pattern: the pattern, the refusal of text that is no pattern, or the limit
 * a pattern is past; which one it is, is told by which of `pattern`, `refused` and `beyond` it has.
 *
 * The three answers are about three different things. A {@link Pattern} is a pattern of the
 * language within the limits every implementation holds to. {@link PatternRefused} is text that is
 * no pattern of the language, and says what in it is not. {@link PatternBeyond} is a pattern of the
 * language written past one of those limits: every construct in it is one the language has, so an
 * author told it is not in the language would go looking for a construct that is not there.
 *
 * A pattern read in part is not an answer: a tree of the constructs that were understood accepts a
 * language the author did not write.
 */
export type PatternRead =
  | { readonly pattern: Pattern }
  | { readonly refused: PatternRefused }
  | { readonly beyond: PatternBeyond };

/** A pattern that was read, as the strings it accepts. Only {@link readPattern} makes one. */
export interface Pattern {
  /**
   * Whether the whole of `subject` is one of the strings the pattern accepts.
   *
   * The subject is read a scalar value at a time, once, and never gone back over: the machine the
   * pattern means is walked as the set of states it may be in, so a match takes time linear in the
   * subject. Where a character leads from the set the walk is in is kept once it is worked out, so a
   * walk that comes to the set again with the same character looks it up, which is what most
   * characters of most subjects cost; what is kept is held by the pattern from one match to the
   * next, within about two megabytes, and changes how fast a match is and never what it answers. A
   * character that leads somewhere not yet worked out costs moving each state of the set, at most
   * the machine's 250,000. The machine is built the first time the pattern is matched.
   *
   * `subject` may be any string, and one holding half of a surrogate pair with no other half beside
   * it is no text and is accepted by nothing.
   */
  matches(subject: string): boolean;
}

/**
 * What `text` means as a pattern, or what makes it no pattern, or which limit it is past.
 *
 * A limit is about a pattern, so it is answered only once the text is known to be one: a count or a
 * depth past its limit is noted where it is met and the reading goes on to the end, and text that is
 * no pattern anywhere in it is refused whatever limit it also went past. Of the limits, the first one
 * met in the text, left to right, is the answer, and the states are counted last, of a pattern within
 * the other two.
 *
 * The reading has no depth of its own: groups are read with a stack rather than by recursion, and
 * the anchors are placed the same way, so text nested as deeply as it is long is read to its end.
 *
 * `text` may be any string: half of a surrogate pair in it with no other half beside it is a
 * character no string holds, and is refused as one. Where a refusal or a limit points is a UTF-16
 * index into `text`.
 */
export function readPattern(text: string): PatternRead {
  if (typeof text !== "string") {
    throw new TypeError("a pattern is read from a string");
  }
  const read = readText(text);
  return "meaning" in read ? { pattern: new ReadPattern(read.meaning) } : read;
}

/**
 * A pattern and what its matches work out: the machine, built at the first match, and the walk the
 * matches go in, which keeps the sets of states they came to. A match runs to its end before another
 * can begin, so one walk serves every match of the pattern.
 */
class ReadPattern implements Pattern {
  readonly #meaning: Meaning;
  #walk: Walk | undefined;

  constructor(meaning: Meaning) {
    this.#meaning = meaning;
  }

  matches(subject: string): boolean {
    if (typeof subject !== "string") {
      throw new TypeError("a pattern matches a string");
    }
    this.#walk ??= new Walk(build(this.#meaning));
    return this.#walk.matches(subject);
  }
}
