// The machine a pattern's meaning is run as, and the walk a match takes over it.

import { type Meaning, NO_CEILING } from "./pattern_meaning.ts";
import { has, type Symbols } from "./pattern_symbols.ts";

/**
 * The strings a pattern accepts, as states to walk between: an automaton with steps that cost a
 * symbol out of a set, and steps that cost nothing. A choice is a step into either arm and a
 * repetition a step back to where it started, so the machine has the states its pattern is counted
 * at and no more.
 *
 * What labels a step is a set of symbols and never one, so a step is as cheap for [^a] as for a.
 *
 * A state is a place in arrays and nothing made: a state's steps are from its place in `stepStart`
 * to the next state's, and its steps for nothing are from its place in `freeStart` to the next
 * state's, each to `freeTo`. A step over one code point holds it in `stepChar`, and a step over any
 * other set holds -1 there and the set in `stepSet`. So a machine of 250,000 states is as many
 * places, and its steps as many more, however many states have none.
 */
export class Machine {
  readonly stepStart: Int32Array;
  readonly stepTo: Int32Array;
  readonly stepChar: Int32Array;
  readonly stepSet: readonly (Symbols | null)[];
  readonly freeStart: Int32Array;
  readonly freeTo: Int32Array;
  readonly accept: number;

  constructor(laying: Laying, accept: number) {
    const states = laying.states;
    this.accept = accept;
    this.stepStart = new Int32Array(states + 1);
    for (const from of laying.stepFrom) {
      this.stepStart[from + 1]!++;
    }
    for (let q = 0; q < states; q++) {
      this.stepStart[q + 1]! += this.stepStart[q]!;
    }
    this.stepTo = new Int32Array(laying.stepTo.length);
    this.stepChar = new Int32Array(laying.stepTo.length);
    const stepSet = new Array<Symbols | null>(laying.stepTo.length).fill(null);
    const filled = this.stepStart.slice(0, states);
    laying.stepFrom.forEach((from, i) => {
      const at = filled[from]!++;
      this.stepTo[at] = laying.stepTo[i]!;
      const over = laying.stepOver[i]!;
      if (over.length === 2 && over[0] === over[1]) {
        this.stepChar[at] = over[0]!;
      } else {
        this.stepChar[at] = -1;
        stepSet[at] = over;
      }
    });
    this.stepSet = stepSet;
    this.freeStart = new Int32Array(states + 1);
    for (const from of laying.freeFrom) {
      this.freeStart[from + 1]!++;
    }
    for (let q = 0; q < states; q++) {
      this.freeStart[q + 1]! += this.freeStart[q]!;
    }
    this.freeTo = new Int32Array(laying.freeTo.length);
    filled.set(this.freeStart.subarray(0, states));
    laying.freeFrom.forEach((from, i) => {
      this.freeTo[filled[from]!++] = laying.freeTo[i]!;
    });
  }

  /** How many states the machine has. */
  get size(): number {
    return this.stepStart.length - 1;
  }

  /** Whether the step at `at` is over `cp`. */
  stepsOver(at: number, cp: number): boolean {
    const c = this.stepChar[at]!;
    return c >= 0 ? c === cp : has(this.stepSet[at]!, cp);
  }
}

/**
 * The machine `meaning` is run as. It refuses nothing: the meaning was read within the limit on
 * states, and building makes no more states than the count.
 */
export function build(meaning: Meaning): Machine {
  const laying = new Laying();
  const start = laying.state();
  return new Machine(laying, laying.build(meaning, start));
}

/**
 * A machine's states and steps while they are made, before they are laid out a state at a time.
 * Each step is written where it is made, out of whichever state it leaves; nothing is made for a
 * state but its number.
 */
class Laying {
  states = 0;
  readonly stepFrom: number[] = [];
  readonly stepTo: number[] = [];
  readonly stepOver: Symbols[] = [];
  readonly freeFrom: number[] = [];
  readonly freeTo: number[] = [];

  state(): number {
    return this.states++;
  }

  /**
   * Makes the states for `m`, walked into from `from`, and answers where it leaves off: one entry and
   * one exit apiece, which is what lets the shapes compose without any of them knowing what it is
   * inside. Recursive, since a pattern that was read nests no deeper than the nesting depth.
   */
  build(m: Meaning, from: number): number {
    switch (m.kind) {
      case "nothing":
        return from;
      case "never":
        // Nothing leads out of it, so nothing after it is reached.
        return this.state();
      case "symbols":
        return this.step(from, m.set);
      case "literalRun": {
        let at = from;
        for (const c of m.chars) {
          at = this.step(at, [c, c]);
        }
        return at;
      }
      case "inTurn": {
        let at = from;
        for (const part of m.parts) {
          at = this.build(part, at);
        }
        return at;
      }
      case "eitherOf": {
        const out = this.state();
        for (const arm of m.parts) {
          const into = this.state();
          this.freely(from, into);
          this.freely(this.build(arm, into), out);
        }
        return out;
      }
      case "repeated":
        return this.repeated(m.part, m.least, m.most, from);
    }
  }

  private step(from: number, over: Symbols): number {
    const to = this.state();
    this.stepFrom.push(from);
    this.stepTo.push(to);
    this.stepOver.push(over);
    return to;
  }

  private freely(from: number, to: number): void {
    this.freeFrom.push(from);
    this.freeTo.push(to);
  }

  /**
   * A repetition as the copies it is: the floor is copies one after another, what is above it is
   * copies each of which may be stepped over, and an unbounded ceiling is one more copy with a step
   * back to where it began.
   */
  private repeated(what: Meaning, least: number, most: number, from: number): number {
    // A body that makes no state is the empty string however many times it is taken, and is built as
    // that: one state to end in. Copied a count at a time it would cost the count and make nothing.
    if (buildsNoState(what)) {
      const out = this.state();
      this.freely(from, out);
      return out;
    }
    let at = from;
    for (let i = 0; i < least; i++) {
      at = this.build(what, at);
    }
    if (most === NO_CEILING) {
      const loop = this.state();
      this.freely(at, loop);
      this.freely(this.build(what, loop), loop);
      return loop;
    }
    const out = this.state();
    this.freely(at, out);
    for (let i = least; i < most; i++) {
      at = this.build(what, at);
      this.freely(at, out);
    }
    return out;
  }
}

/** Whether building `m` makes no state, which is only ever the empty string. */
function buildsNoState(m: Meaning): boolean {
  switch (m.kind) {
    case "nothing":
      return true;
    case "inTurn":
      return m.parts.every(buildsNoState);
    default:
      return false;
  }
}

/**
 * The states a walk is in: a sparse set, the states in it listed in `dense` up to `length` and each
 * one's place in that list in `sparse`. Emptying it is forgetting the list, so a walk over a machine
 * of many states that is in few of them costs the few.
 */
export class StateSet {
  readonly dense: Int32Array;
  readonly sparse: Int32Array;
  length = 0;

  constructor(size: number) {
    this.dense = new Int32Array(size);
    this.sparse = new Int32Array(size);
  }

  has(q: number): boolean {
    const i = this.sparse[q]!;
    return i < this.length && this.dense[i] === q;
  }

  /** Puts `q` in the set, which does not hold it. */
  add(q: number): void {
    this.sparse[q] = this.length;
    this.dense[this.length++] = q;
  }

  clear(): void {
    this.length = 0;
  }
}
