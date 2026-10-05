// The walk a match takes over a machine, and the sets of states it keeps for the next.

import { type Machine, StateSet } from "./pattern_machine.ts";

/**
 * About how much one walk keeps of the sets it has worked out, before it forgets them and starts
 * again. Past it are kept only the set a walk starts in and the set the walk is in, with the step
 * between them, which are kept whatever they take: the machine bounds what they take, and a walk
 * that keeps coming back to a set larger than the room reads it by kept steps.
 */
export const KNOWN_BYTES = 2 << 20;

/** What one {@link KnownSet} takes besides its states, about: its ASCII steps and the rest of it. */
const KNOWN_SET_BYTES = 8 * 0x80 + 64;

/** What one step over a character past ASCII takes in the table of them, about. */
const OTHER_STEP_BYTES = 48;

/**
 * A set of states a walk has been in, and where the ASCII characters it has read from it lead: the
 * sets of states a deterministic machine would have, made only as a walk comes to them. Where the
 * other characters lead is kept for all sets in one table, {@link KnownSets.others}.
 *
 * A set leads to another by reference and not by a place in a list, so a set that is forgotten is
 * still the set it was to whatever holds it: nothing can be held that names a different set.
 */
export class KnownSet {
  /**
   * The states of the set, every step for nothing already taken, in the order the walk put them in,
   * which says nothing about the set.
   */
  readonly states: Int32Array;
  /** The sum of `scatter` over the states, by which the set is looked up. */
  readonly hash: number;
  /**
   * Names the set in the table of steps over other characters: from 1, in the order the sets were
   * kept since they were last forgotten.
   */
  id = 0;
  readonly accepts: boolean;
  /** Whether the set has no state, so that no string from here on is accepted. */
  readonly none: boolean;
  /** Where each ASCII character leads, or null where that is not known yet. */
  ascii: (KnownSet | null)[] = new Array<KnownSet | null>(0x80).fill(null);

  constructor(states: Int32Array, hash: number, accepts: boolean) {
    this.states = states;
    this.hash = hash;
    this.accepts = accepts;
    this.none = states.length === 0;
  }
}

/**
 * The sets one walk has worked out, and nothing else: what is here is what was worked out, and never
 * what a walk decided from how often it was looked up. Which sets are kept, and whether any are,
 * changes how fast a walk is and no answer.
 *
 * Each method that keeps something, a set (`keep`) or a step (`lead`), keeps it where it fits in the
 * room, and otherwise keeps nothing and says so: none of them forgets anything to make room. What to
 * do when something does not fit is the match's to decide, in one place ({@link Walk.forgets}).
 *
 * The sets every walk needs, the set it starts in and the set it is in when the others are forgotten
 * for it, are kept whatever they take, and so is the step between them; they are counted apart, in
 * `needed`, and only `afresh` keeps them, which forgets everything else first. The room bounds
 * everything else. So a walk goes on by kept steps however large its sets are, and what is kept is
 * at most the room and those two sets, at most twice the machine's states.
 *
 * A set is looked up in `slots` by its hash, a sum over its states that is the same in whatever order
 * the walk put them in, so the set is never put in order.
 */
export class KnownSets {
  readonly room: number;
  /** Each kept set at its hash or past it, a power of two of them and at least twice as many as are kept. */
  slots: (KnownSet | null)[] = [];
  /** The set a walk starts in, or null where it is not known. */
  first: KnownSet | null = null;
  /** Where each character past ASCII leads from each kept set, where that is known, by {@link otherKey}. */
  others = new Map<number, KnownSet>();
  /** How many sets are kept; `bytes` what they and their steps take in the room, `needed` what the sets every walk needs take beside it. */
  kept = 0;
  bytes = 0;
  needed = 0;

  constructor(room: number) {
    this.room = room;
  }

  /**
   * Starts the kept sets again from what every walk needs, and answers the set `now` holds, whose
   * hash is `hash`. It forgets every set but the one a walk starts in, and every step, and keeps
   * `now` beside that one, whatever it takes, with the step over `cp` to it where `from` is the set a
   * walk starts in; a walk that has kept nothing keeps `now` as the set it starts in.
   */
  afresh(m: Machine, now: StateSet, hash: number, from: KnownSet | null, cp: number): KnownSet {
    const first = this.first;
    this.slots = [];
    this.first = null;
    this.others = new Map();
    this.kept = 0;
    this.bytes = 0;
    this.needed = 0;
    if (first === null) {
      const set = made(m, now, hash);
      this.put(set, true);
      this.first = set;
      return set;
    }
    first.ascii = new Array<KnownSet | null>(0x80).fill(null);
    this.put(first, true);
    this.first = first;
    let set = this.find(now, hash);
    if (set === null) {
      set = made(m, now, hash);
      this.put(set, true);
    }
    if (from === first) {
      if (cp < 0x80) {
        first.ascii[cp] = set;
      } else {
        this.others.set(otherKey(first.id, cp), set);
        this.needed += OTHER_STEP_BYTES;
      }
    }
    return set;
  }

  /** What the slots grow by, in bytes, to keep one more set, or nought. */
  private slotsGrowth(): number {
    return (this.kept + 1) * 2 <= this.slots.length ? 0 : 8 * Math.max(16, this.slots.length);
  }

  /** Whether `more` bytes fit in the room beside what is kept. */
  private fits(more: number): boolean {
    return this.bytes + more <= this.room;
  }

  /**
   * The set `now` holds, whose hash is `hash`: found where it is kept, and otherwise kept in the room.
   * It is null where it does not fit beside the sets kept.
   */
  keep(m: Machine, now: StateSet, hash: number): KnownSet | null {
    const found = this.find(now, hash);
    if (found !== null) {
      return found;
    }
    if (!this.fits(setBytes(now.length) + this.slotsGrowth())) {
      return null;
    }
    const set = made(m, now, hash);
    this.put(set, false);
    return set;
  }

  /** Keeps `set`, which is not kept, charging its states and what the slots grow by, beside the room where `needed`. */
  private put(set: KnownSet, needed: boolean): void {
    if ((this.kept + 1) * 2 > this.slots.length) {
      this.grow(needed);
    }
    this.kept++;
    set.id = this.kept;
    this.slots[this.free(set.hash)] = set;
    this.charge(setBytes(set.states.length), needed);
  }

  private charge(more: number, needed: boolean): void {
    if (needed) {
      this.needed += more;
    } else {
      this.bytes += more;
    }
  }

  /** Where `cp` leads from `from`, or null where that is not known. */
  step(from: KnownSet, cp: number): KnownSet | null {
    return cp < 0x80 ? from.ascii[cp]! : this.others.get(otherKey(from.id, cp)) ?? null;
  }

  /**
   * Keeps that `cp` leads from `from` to `to`, both kept, and answers whether it did: a step over
   * ASCII has its room in `from`, and another is kept where it fits in the room.
   */
  lead(from: KnownSet, cp: number, to: KnownSet): boolean {
    if (cp < 0x80) {
      from.ascii[cp] = to;
      return true;
    }
    if (!this.fits(OTHER_STEP_BYTES)) {
      return false;
    }
    this.others.set(otherKey(from.id, cp), to);
    this.bytes += OTHER_STEP_BYTES;
    return true;
  }

  /**
   * The kept set that is the set `now` holds, whose hash is `hash`, or null where it is not kept: the
   * slots from `hash` on are probed until an empty one.
   */
  find(now: StateSet, hash: number): KnownSet | null {
    if (this.slots.length === 0) {
      return null;
    }
    const mask = this.slots.length - 1;
    for (let at = hash & mask; this.slots[at] !== null; at = (at + 1) & mask) {
      const held = this.slots[at]!;
      if (held.hash === hash && same(held, now)) {
        return held;
      }
    }
    return null;
  }

  /** The first empty slot from `hash` on. */
  private free(hash: number): number {
    const mask = this.slots.length - 1;
    let at = hash & mask;
    while (this.slots[at] !== null) {
      at = (at + 1) & mask;
    }
    return at;
  }

  /** Makes twice the slots, or sixteen, and puts each kept set in them again by its hash. */
  private grow(needed: boolean): void {
    const old = this.slots;
    this.slots = new Array<KnownSet | null>(Math.max(16, 2 * old.length)).fill(null);
    this.charge(8 * (this.slots.length - old.length), needed);
    for (const set of old) {
      if (set !== null) {
        this.slots[this.free(set.hash)] = set;
      }
    }
  }
}

/** The key of the step over `cp` from the set whose id is `id`: within the integers a number holds exactly. */
function otherKey(id: number, cp: number): number {
  return id * 0x110000 + cp;
}

/** What a kept set of `n` states takes: its states and the rest of it. */
function setBytes(n: number): number {
  return 4 * n + KNOWN_SET_BYTES;
}

/** A new set of the states `now` holds, whose hash is `hash`, kept nowhere yet. */
function made(m: Machine, now: StateSet, hash: number): KnownSet {
  return new KnownSet(now.dense.slice(0, now.length), hash, now.has(m.accept));
}

/**
 * Whether `held` is the set `now` holds: as many states, each of which `now` has, asked one at a time.
 * Sets with the same hash are told apart here, so a hash shared by two sets changes no answer.
 */
function same(held: KnownSet, now: StateSet): boolean {
  if (held.states.length !== now.length) {
    return false;
  }
  for (const q of held.states) {
    if (!now.has(q)) {
      return false;
    }
  }
  return true;
}

/** The sum of `scatter` over the states `now` holds, summed where a set is looked up. */
export function hashOf(now: StateSet): number {
  let hash = 0;
  for (let i = 0; i < now.length; i++) {
    hash = (hash + scatter(now.dense[i]!)) >>> 0;
  }
  return hash;
}

/** A state's part of the hash of a set it is in. */
function scatter(q: number): number {
  const mixed = Math.imul(q, 0x9E3779B9) >>> 0;
  return (mixed ^ (mixed >>> 15)) >>> 0;
}

/**
 * The room one match works in: the sets of states it moves between, the sets it has already worked
 * out where a character leads from, and which of the two it is going by.
 *
 * The kept sets outlast a match, and nothing else here does: what a match decides from how often it
 * looked them up (`forgot`, `frozen`, `worked` and `read`) starts again with the next match, in
 * `begin`. One match that keeps coming to new sets therefore slows no match after it.
 */
export class Walk {
  private readonly m: Machine;
  private now: StateSet;
  private next: StateSet;
  private readonly pending: Int32Array;
  readonly known: KnownSets;
  /** The kept set the walk is in, or null where it is going on without kept sets and is in `now`. */
  private in: KnownSet | null = null;
  /**
   * Whether this match has forgotten the kept sets to make room, and whether it keeps no more of
   * them: it goes on by those kept, and a state at a time where they do not lead, until it comes back
   * to one.
   */
  private forgot = false;
  private frozen = false;
  /**
   * The characters this match worked out a state at a time to keep, and the characters it read by
   * kept steps, since it last forgot the kept sets or since it began.
   */
  private worked = 0;
  private read = 0;

  constructor(m: Machine, room = KNOWN_BYTES) {
    this.m = m;
    this.now = new StateSet(m.size);
    this.next = new StateSet(m.size);
    // A state is pending at most once at a time, so the machine's states are room enough.
    this.pending = new Int32Array(m.size);
    this.known = new KnownSets(room);
  }

  /**
   * Whether the whole of `subject` is accepted: every state the machine may be in is walked at once,
   * a scalar value at a time, and nothing is gone back over. Half of a surrogate pair with no other
   * half beside it is no text, and no step is over it.
   *
   * An ASCII character whose step from the kept set the walk is in is known is taken here, as one
   * lookup. Every other character is taken by `take`.
   */
  matches(subject: string): boolean {
    this.begin();
    for (let at = 0; at < subject.length;) {
      const c = subject.charCodeAt(at);
      if (c < 0x80 && this.in !== null) {
        const next = this.in.ascii[c]!;
        if (next !== null) {
          this.in = next;
          this.read++;
          if (next.none) {
            return false;
          }
          at++;
          continue;
        }
      }
      const cp = subject.codePointAt(at)!;
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        return false;
      }
      at += cp > 0xFFFF ? 2 : 1;
      if (!this.take(cp)) {
        return false;
      }
    }
    return this.in !== null ? this.in.accepts : this.now.has(this.m.accept);
  }

  /**
   * Starts a match: it keeps sets again, whatever the match before it decided, and puts the walk in
   * the state it starts in, with every state the steps for nothing reach from it, as the kept set it
   * starts in.
   */
  private begin(): void {
    this.forgot = false;
    this.frozen = false;
    this.worked = 0;
    this.read = 0;
    this.in = this.known.first;
    if (this.in !== null) {
      return;
    }
    // Only a walk that has kept nothing comes here: starting the kept sets again keeps this one.
    this.now.clear();
    this.enter(this.now, 0);
    this.in = this.known.afresh(this.m, this.now, hashOf(this.now), null, 0);
  }

  /**
   * Moves the walk over one symbol, and answers false where it is in no state after it. Where the set
   * it is in is kept and where `cp` leads from it is known, that is where it goes; otherwise its
   * states are moved by `advance` and the set they come to is held, with the step to it.
   */
  private take(cp: number): boolean {
    const from = this.in;
    if (from !== null) {
      const next = this.known.step(from, cp);
      if (next !== null) {
        this.read++;
        this.in = next;
        return !next.none;
      }
      this.advance(from.states, from.states.length, cp);
      if (!this.frozen) {
        this.worked++;
      }
    } else {
      this.advance(this.now.dense, this.now.length, cp);
    }
    const next = this.hold(from, cp);
    this.in = next;
    if (next !== null) {
      return !next.none;
    }
    return this.now.length > 0;
  }

  /**
   * The kept set the walk has come to over `cp`, in `now`, from the kept set `from` or from no kept
   * set where `from` is null: found where it is kept, and otherwise kept now, with the step to it. It
   * is null where the match keeps no more sets and this one is not kept, and the walk goes on from
   * `now` a state at a time. This is the one place a walk keeps anything, and so the one place it
   * learns that something does not fit.
   */
  private hold(from: KnownSet | null, cp: number): KnownSet | null {
    const hash = hashOf(this.now);
    if (this.frozen) {
      return this.known.find(this.now, hash);
    }
    const set = this.known.keep(this.m, this.now, hash);
    if (set === null) {
      return this.forgets(hash, from, cp);
    }
    if (from === null || this.known.lead(from, cp, set)) {
      return set;
    }
    // The step does not fit; where the match keeps no more, it goes on from the set it is in, which
    // is kept, with no step to it.
    return this.forgets(hash, from, cp) ?? set;
  }

  /**
   * Decides, for this match only, what is done when a set or a step does not fit beside those kept:
   * the set in `now` is kept beside the set a walk starts in once the others are forgotten, or null
   * where they are not and the match is frozen. Nothing else forgets them, and nothing else freezes a
   * match.
   *
   * The first time, the kept sets are forgotten: they may be another match's, and say nothing of this
   * one. After that, they are forgotten again where what this match read by kept steps since it last
   * forgot them is ten times what it worked out a state at a time to keep; otherwise keeping them
   * saves nothing, and the match keeps no more but goes on by those it has.
   */
  private forgets(hash: number, from: KnownSet | null, cp: number): KnownSet | null {
    if (this.forgot && this.read < 10 * this.worked) {
      this.frozen = true;
      return null;
    }
    this.forgot = true;
    this.worked = 0;
    this.read = 0;
    return this.known.afresh(this.m, this.now, hash, from, cp);
  }

  /**
   * Puts the walk, in `now`, where the first `count` states of `from` lead over one symbol: from each
   * state, each step over `cp`, and the states the steps for nothing reach from where those lead.
   * `from` is `now`'s states or a kept set's, which is moved from as it is. Its work is the steps out
   * of `from` and the states it comes to, at most the machine's.
   */
  private advance(from: Int32Array, count: number, cp: number): void {
    const m = this.m;
    this.next.clear();
    for (let i = 0; i < count; i++) {
      const q = from[i]!;
      for (let at = m.stepStart[q]!; at < m.stepStart[q + 1]!; at++) {
        if (m.stepsOver(at, cp)) {
          this.enter(this.next, m.stepTo[at]!);
        }
      }
    }
    [this.now, this.next] = [this.next, this.now];
  }

  /** Puts `q` in `into`, with every state the steps for nothing reach from it, each once. */
  private enter(into: StateSet, q: number): void {
    if (into.has(q)) {
      return;
    }
    const m = this.m;
    into.add(q);
    let pending = 0;
    this.pending[pending++] = q;
    while (pending > 0) {
      const from = this.pending[--pending]!;
      for (let at = m.freeStart[from]!; at < m.freeStart[from + 1]!; at++) {
        const to = m.freeTo[at]!;
        if (!into.has(to)) {
          into.add(to);
          this.pending[pending++] = to;
        }
      }
    }
  }
}
