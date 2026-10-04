// What a pattern means, and what its reader holds before the anchors are placed.

import type { Symbols } from "./pattern_symbols.ts";
import { plusStates, repeatedStates } from "./pattern_states.ts";

/**
 * What a pattern means: the set of strings it accepts, as regular-language operations over sets of
 * scalar values. The one form a pattern takes past its reader; the machine is built from it and
 * never from the text.
 *
 * What is kept is what the language depends on and nothing else. One written character is one
 * symbol, a set of one, and a literal, a class, a negated class, a shorthand and . all arrive as
 * symbols, told apart only by the set. Characters of one symbol each, one after another, are one
 * `literalRun`: the machine takes a step for each of them, and the meaning holds them in one list
 * rather than a meaning for each. An anchor is gone: whole-string matching settles what each comes
 * to where the pattern is read. Whether a repetition is greedy or reluctant and whether a group
 * captures say what an engine does on the way and not which strings come out, so none of them is
 * here either.
 */
export type Meaning =
  /** The one string of no symbols. */
  | { readonly kind: "nothing" }
  /** No string at all, which is what an anchor nobody can satisfy leaves. */
  | { readonly kind: "never" }
  /** One symbol out of a set of them. */
  | { readonly kind: "symbols"; readonly set: Symbols }
  /**
   * Its characters one after another, two or more: what an `inTurn` of a `symbols` for each would
   * be. One character is a `symbols` and nothing else, so that a pattern has one meaning.
   */
  | { readonly kind: "literalRun"; readonly chars: readonly number[] }
  /** Its parts one after another. */
  | InTurn
  /** Any one of its arms, two or more, every arm and not the first. */
  | { readonly kind: "eitherOf"; readonly parts: readonly Meaning[] }
  /** The same thing some number of times over, both ends carried. */
  | {
    readonly kind: "repeated";
    readonly part: Meaning;
    readonly least: number;
    /** {@link NO_CEILING} for a repetition nothing caps. */
    readonly most: number;
  };

export interface InTurn {
  readonly kind: "inTurn";
  readonly parts: readonly Meaning[];
}

/** What *, + and {n,} put where a repetition's most is: a bound nothing reaches rather than a large one. */
export const NO_CEILING = -1;

export const NOTHING: Meaning = { kind: "nothing" };

/**
 * What a part is, as far as the anchors around it ask: whether it accepts any string of one symbol or
 * more (`may`), whether every string it accepts has a symbol in it (`must`), and whether it holds an
 * anchor (`holds`). For a `run`, `every` is whether each of the parts it stands for must take a
 * symbol, which is what an anchor beside them asks of them one at a time; `must` is whether any
 * does, as for any sequence.
 */
export interface Facts {
  readonly may: boolean;
  readonly must: boolean;
  readonly holds: boolean;
  readonly every: boolean;
}

/**
 * A pattern as its reader holds it before the anchors are placed. The one shape here a meaning does
 * not have is the anchor, whose answer is settled by where it stands among the rest of the pattern,
 * which is not known until the whole of it is read. Everything else is already its meaning: a part
 * that holds no anchor is made a `meant` as it is read ({@link inTurnOf}, {@link eitherOfOf},
 * {@link repeatedOf}), so a pattern with no anchor is one `meant` once it is read, and only the parts
 * holding an anchor, and the runs of parts between them (`run`), are left as anything else.
 *
 * What the anchors around a part ask of it, and the states it is counted at, are worked out as it is
 * made, from its parts', which were made before it: nothing walks the tree to find them, and nothing
 * recurses however deep the text nests.
 */
export type Written =
  | { readonly kind: "meant"; readonly meaning: Meaning; readonly facts: Facts; readonly states: number }
  /** ^, or $ where `end`. */
  | { readonly kind: "anchor"; readonly end: boolean; readonly facts: Facts; readonly states: number }
  /**
   * Two or more parts of a sequence one after another, none of which holds an anchor, standing among
   * parts that do: its meaning is an `inTurn` of what they mean, which is put in the sequence a part
   * at a time once the anchors are placed.
   */
  | { readonly kind: "run"; readonly meaning: InTurn; readonly facts: Facts; readonly states: number }
  | { readonly kind: "inTurn"; readonly parts: readonly Written[]; readonly facts: Facts; readonly states: number }
  | { readonly kind: "eitherOf"; readonly parts: readonly Written[]; readonly facts: Facts; readonly states: number }
  | {
    readonly kind: "repeated";
    readonly part: Written;
    readonly least: number;
    readonly most: number;
    readonly facts: Facts;
    readonly states: number;
  };

export const NO_FACTS: Facts = { may: false, must: false, holds: false, every: false };

/** A leaf the reader reads, nothing or a set of symbols. */
export function meant(meaning: Meaning): Written {
  const takes = meaning.kind === "symbols";
  return {
    kind: "meant",
    meaning,
    facts: { may: takes, must: takes, holds: false, every: false },
    states: takes ? 1 : 0,
  };
}

/** ^, or $ where `end`. */
export function anchorOf(end: boolean): Written {
  return { kind: "anchor", end, facts: { ...NO_FACTS, holds: true }, states: 1 };
}

/**
 * The meaning of each ASCII character written as itself, which every pattern shares, since nothing
 * writes to a meaning once it is made.
 */
const ASCII_LITERALS: readonly Meaning[] = Array.from({ length: 0x80 },
  (_, cp) => ({ kind: "symbols", set: [cp, cp] }) as const);

/** The meaning of the one character `cp` written as itself. */
export function literalMeaning(cp: number): Meaning {
  return cp < 0x80 ? ASCII_LITERALS[cp]! : { kind: "symbols", set: [cp, cp] };
}

/** `set` as what it means. One ASCII character, however written, as \| or [a] is, means what it means written as itself. */
export function symbolsMeaning(set: Symbols): Meaning {
  return set.length === 2 && set[0] === set[1] && set[0]! < 0x80
    ? ASCII_LITERALS[set[0]!]!
    : { kind: "symbols", set };
}

/**
 * `chars` one after another, each a code point: one character is a `symbols`, and two or more a
 * `literalRun` holding `chars`, which is its own from here.
 */
export function charactersMeaning(chars: readonly number[]): Meaning {
  return chars.length === 1 ? literalMeaning(chars[0]!) : { kind: "literalRun", chars };
}

/**
 * `parts` one after another, two or more, none of them nothing. Where none holds an anchor it is
 * what they mean in turn, put together as placing the anchors puts a sequence together.
 */
export function inTurnOf(parts: readonly Written[]): Written {
  let may = false;
  let must = false;
  let holds = false;
  let states = 0;
  for (const part of parts) {
    may ||= part.facts.may;
    must ||= part.facts.must;
    holds ||= part.facts.holds;
    states = plusStates(states, part.states);
  }
  const facts = { may, must, holds, every: false };
  if (!holds) {
    return { kind: "meant", meaning: inTurnMeaningOf(meaningsOf(parts)), facts, states };
  }
  return { kind: "inTurn", parts, facts, states };
}

/** Any one of `arms`, two or more; what they mean where none holds an anchor. */
export function eitherOfOf(arms: readonly Written[]): Written {
  let may = false;
  let must = true;
  let holds = false;
  let states = 1;
  for (const arm of arms) {
    may ||= arm.facts.may;
    must &&= arm.facts.must;
    holds ||= arm.facts.holds;
    states = plusStates(states, plusStates(1, arm.states));
  }
  const facts = { may, must, holds, every: false };
  if (!holds) {
    return { kind: "meant", meaning: { kind: "eitherOf", parts: meaningsOf(arms) }, facts, states };
  }
  return { kind: "eitherOf", parts: arms, facts, states };
}

/** `one` from `least` to `most` times; what that means where `one` holds no anchor. */
export function repeatedOf(one: Written, least: number, most: number): Written {
  const facts = {
    may: (most === NO_CEILING || most > 0) && one.facts.may,
    must: least > 0 && one.facts.must,
    holds: one.facts.holds,
    every: false,
  };
  const states = repeatedStates(one.states, least, most);
  if (!facts.holds) {
    return {
      kind: "meant",
      meaning: { kind: "repeated", part: meaningOf(one), least, most },
      facts,
      states,
    };
  }
  return { kind: "repeated", part: one, least, most, facts, states };
}

/** What `w` means, a `meant`: a part that holds no anchor is one as it is made. */
export function meaningOf(w: Written): Meaning {
  if (w.kind !== "meant") {
    throw new Error(`a part holding no anchor is meant, and this is ${w.kind}`);
  }
  return w.meaning;
}

function meaningsOf(ws: readonly Written[]): Meaning[] {
  return ws.map(meaningOf);
}

/**
 * `made` one after another, with every part that is nothing left out: no part is nothing, nothing
 * alone, and one part itself.
 */
export function inTurnMeaningOf(made: readonly Meaning[]): Meaning {
  const parts = made.filter((one) => one.kind !== "nothing");
  switch (parts.length) {
    case 0:
      return NOTHING;
    case 1:
      return parts[0]!;
    default:
      return { kind: "inTurn", parts };
  }
}
