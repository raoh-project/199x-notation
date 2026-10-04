// The states a pattern comes to with its repetitions written out, counted from what is written and
// without building anything. This is the measure the limit on a machine's states is stated in, and
// it is the specifications' and not this implementation's: every implementation counts the same
// number from the same text. Counted on the pattern as written:
//
//   - a character, an escape, ., a shorthand and a class are one each; so are ^ and $;
//   - an empty pattern, group or alternative is none;
//   - a sequence is the sum of its parts, and a group is what is inside it;
//   - a choice of n alternatives is one more than the sum of one more than each alternative;
//   - A{n,m} is m times A, plus one; A{n} is A{n,n} and A? is A{0,1};
//   - A{n,} is n + 1 times A, plus one; A* is A{0,} and A+ is A{1,};
//   - the pattern is one more than what it is written as.
//
// For a pattern without anchors this is the states of the machine its shape builds, and an anchor
// counts one and comes to at most one state, so a pattern within the limit always has a machine.
// Counted up to one past the limit and no further, so a count as large as the reader reads is
// multiplied within the integers a number holds exactly.
//
// Counted as each part is read, from its parts' counts, so nothing walks the tree.

/** The most states a pattern is counted at and is within the limit. */
export const MOST_STATES = 250_000;

/** One past the limit: every count above the limit is this. */
const PAST_STATES = MOST_STATES + 1;

/** The states a part of `states` comes to as a whole pattern, or one past the limit. */
export function wholeStates(states: number): number {
  return plusStates(1, states);
}

/**
 * A repetition of a body of `body` states: its copies, and the state it ends in. `most` is -1 for a
 * repetition nothing caps.
 */
export function repeatedStates(body: number, least: number, most: number): number {
  const copies = most < 0 ? least + 1 : most;
  return plusStates(timesStates(copies, body), 1);
}

export function plusStates(one: number, other: number): number {
  return Math.min(PAST_STATES, one + other);
}

function timesStates(copies: number, body: number): number {
  if (copies === 0 || body === 0) {
    return 0;
  }
  if (copies > Math.floor(PAST_STATES / body)) {
    return PAST_STATES;
  }
  return Math.min(PAST_STATES, copies * body);
}
