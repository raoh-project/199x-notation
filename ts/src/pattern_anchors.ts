// What the anchors in a pattern come to, given that the whole of it must match the whole string.
//
// Whole-string matching is what gives an anchor an answer. ^ asks to be at the start of the string,
// so it is satisfied by every string where nothing before it can take a symbol and by none where
// everything before it must: the empty string in the first case and never in the second. $ is the
// same question about the end. Where neither holds, as in (a|)^b, the strings accepted are settled
// by which arm a string took, which the language has no shape for, so the pattern is refused. The
// same for an anchor under a repetition, where how many copies precede it is not a thing the shape
// says.
//
// The reader asks this of text nested as deeply as it is long, before any limit says it is too
// deep, because whether an anchor can be placed is part of whether the text is a pattern. What each
// part may and must take and whether it holds an anchor are worked out as the part is read
// (Written.facts), and a part that holds none is read as what it means, the parts of a sequence
// between two that hold one as one part (a run); so what is left is walked once from the root down,
// with a stack of its own, for where each part holding an anchor and each run beside one stands,
// and what it comes to. A pattern with no anchor is its meaning already, and is not walked.

import { type Meaning, NOTHING, type Written } from "./pattern_meaning.ts";

/** Whether an anchor is at the end it is asking about, as far as the shape says. */
type Where = "yes" | "no" | "unsettled";

/**
 * A part to place, standing where `atStart` and `atEnd` say, or, where `together`, one whose parts
 * are placed and whose meaning is to be put together from them.
 */
interface Placement {
  readonly w: Written;
  readonly atStart: Where;
  readonly atEnd: Where;
  readonly together: boolean;
}

/**
 * The meaning of `w` with every anchor read as what it comes to, or `undefined` where one cannot be
 * settled.
 */
export function placeAnchors(w: Written): Meaning | undefined {
  if (w.kind === "meant") {
    return w.meaning;
  }
  const tasks: Placement[] = [{ w, atStart: "yes", atEnd: "yes", together: false }];
  const results: Meaning[] = [];
  while (tasks.length > 0) {
    const task = tasks.pop()!;
    const w = task.w;
    if (task.together) {
      putTogether(w, results);
      continue;
    }
    switch (w.kind) {
      case "meant":
      case "run":
        results.push(w.meaning);
        break;
      case "anchor": {
        const made = anchor(w.end, w.end ? task.atEnd : task.atStart);
        if (made === undefined) {
          return undefined;
        }
        results.push(made);
        break;
      }
      case "eitherOf":
        // Every arm of a choice begins where the choice begins and ends where it ends.
        tasks.push({ ...task, together: true });
        for (let at = w.parts.length - 1; at >= 0; at--) {
          tasks.push({ w: w.parts[at]!, atStart: task.atStart, atEnd: task.atEnd, together: false });
        }
        break;
      case "inTurn": {
        tasks.push({ ...task, together: true });
        const sides = sidesOf(w.parts, task.atStart, task.atEnd);
        for (let at = w.parts.length - 1; at >= 0; at--) {
          tasks.push({ w: w.parts[at]!, atStart: sides[2 * at]!, atEnd: sides[2 * at + 1]!, together: false });
        }
        break;
      }
      case "repeated":
        if (!w.part.facts.holds) {
          tasks.push({ ...task, together: true });
          tasks.push({ w: w.part, atStart: task.atStart, atEnd: task.atEnd, together: false });
        } else if (w.least === 1 && w.most === 1) {
          // One copy is the thing itself and stands where the repetition stands.
          tasks.push({ w: w.part, atStart: task.atStart, atEnd: task.atEnd, together: false });
        } else {
          // Any other count leaves how many copies come before the anchor to the string.
          return undefined;
        }
        break;
    }
  }
  return results[0];
}

/**
 * What an anchor standing `at` comes to, or `undefined` where that cannot be settled. ^ asks to be at
 * the start of the string and there is one such place, so anything that must take a symbol before it
 * leaves no string at all. A $ with something after it that must take a symbol is refused rather
 * than read the same way: the language keeps the set of patterns it reads, and that set has no
 * pattern of this shape.
 */
function anchor(end: boolean, at: Where): Meaning | undefined {
  switch (at) {
    case "yes":
      return NOTHING;
    case "no":
      return end ? undefined : { kind: "never" };
    case "unsettled":
      return undefined;
  }
}

/** Takes the meanings of `w`'s parts off the end of `results`, and puts `w`'s meaning there. */
function putTogether(w: Written, results: Meaning[]): void {
  switch (w.kind) {
    case "eitherOf": {
      const arms = results.splice(results.length - w.parts.length);
      results.push({ kind: "eitherOf", parts: arms });
      return;
    }
    case "inTurn": {
      // A run is the parts it stands for, put in the sequence one by one, so that a sequence means
      // what it would with each of them a part of its own; characters one after another are one of
      // those parts, and stay one. An anchor that asks for nothing leaves nothing in the sequence,
      // so ^abc$ means what abc means and is the same tree. Where one part is left, it is what the
      // sequence means as it is, a run's meaning too: ^abc$ is the meaning abc was read as.
      const made = results.splice(results.length - w.parts.length);
      const parts: Meaning[] = [];
      let left: Meaning = NOTHING;
      let kept = 0;
      made.forEach((one, at) => {
        if (w.parts[at]!.kind === "run") {
          // A run is made of two or more parts in turn, and nothing else.
          if (one.kind !== "inTurn") {
            throw new Error(`a run means parts in turn, and this means ${one.kind}`);
          }
          parts.push(...one.parts);
        } else if (one.kind !== "nothing") {
          parts.push(one);
        } else {
          return;
        }
        kept++;
        left = one;
      });
      results.push(kept === 1 ? left : kept === 0 ? NOTHING : { kind: "inTurn", parts });
      return;
    }
    case "repeated": {
      const part = results.pop()!;
      results.push({ kind: "repeated", part, least: w.least, most: w.most });
      return;
    }
    default:
      // A leaf has no parts to put together.
      throw new Error(`${w.kind} has no parts to put together`);
  }
}

/**
 * Where each part of a sequence stands, start and end in turn: at the start of the string where
 * nothing before it takes a symbol and the sequence is there, and not there where everything before
 * it must; the same for the end. What stands before each part and after it is gathered once from
 * each end, so that a literal written out a symbol at a time does not cost its length squared.
 */
function sidesOf(parts: readonly Written[], atStart: Where, atEnd: Where): Where[] {
  const count = parts.length;
  const mayBefore = new Array<boolean>(count + 1).fill(false);
  const mustBefore = new Array<boolean>(count + 1).fill(false);
  mustBefore[0] = true;
  for (let at = 0; at < count; at++) {
    mayBefore[at + 1] = mayBefore[at]! || parts[at]!.facts.may;
    mustBefore[at + 1] = mustBefore[at]! && mustEach(parts[at]!);
  }
  const mayAfter = new Array<boolean>(count + 1).fill(false);
  const mustAfter = new Array<boolean>(count + 1).fill(false);
  mustAfter[count] = true;
  for (let at = count - 1; at >= 0; at--) {
    mayAfter[at] = mayAfter[at + 1]! || parts[at]!.facts.may;
    mustAfter[at] = mustAfter[at + 1]! && mustEach(parts[at]!);
  }
  const out: Where[] = [];
  for (let at = 0; at < count; at++) {
    out.push(beyond(mayBefore[at]!, mustBefore[at]!, atStart), beyond(mayAfter[at + 1]!, mustAfter[at + 1]!, atEnd));
  }
  return out;
}

/** Whether every part `w` stands for must take a symbol: `w` itself, or for a run each of its parts. */
function mustEach(w: Written): boolean {
  return w.kind === "run" ? w.facts.every : w.facts.must;
}

/**
 * Where a part stands, given what is on that side of it and where they all stand together. Nothing
 * on that side takes a symbol, so the part stands where they all do. Everything on that side must
 * take one, so it does not. Anything in between and the answer belongs to a string rather than to
 * the pattern.
 */
function beyond(anyTakes: boolean, allTake: boolean, outer: Where): Where {
  return !anyTakes ? outer : allTake ? "no" : "unsettled";
}
