//! What the anchors in a pattern come to, given that the whole of it must match the whole string.
//!
//! Whole-string matching is what gives an anchor an answer. `^` asks to be at the start of the
//! string, so it is satisfied by every string where nothing before it can take a symbol and by none
//! where everything before it must: the empty string in the first case and never in the second. `$`
//! is the same question about the end. Where neither holds, as in `(a|)^b`, the strings accepted are
//! settled by which arm a string took, which the language has no shape for, so the pattern is
//! refused. The same for an anchor under a repetition, where how many copies precede it is not a
//! thing the shape says.
//!
//! The reader asks this of text nested as deeply as it is long, before any limit says it is too
//! deep, because whether an anchor can be placed is part of whether the text is a pattern. So the
//! tree is walked with stacks of its own: once from the leaves up, for what each part may and must
//! take and whether it holds an anchor, and once from the root down, for where each part stands and
//! what it comes to.

use alloc::vec;
use alloc::vec::Vec;

use super::tree::{Part, Tree};

/// Whether an anchor is at the end it is asking about, as far as the shape says.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Where {
    Yes,
    No,
    Unsettled,
}

/// What a part is, as far as the anchors around it ask: whether it accepts any string of one symbol
/// or more, whether every string it accepts has a symbol in it, and whether it holds an anchor.
#[derive(Clone, Copy, Default)]
struct Facts {
    may: bool,
    must: bool,
    holds: bool,
}

/// A part to place, standing where `at_start` and `at_end` say, or, where `together`, one whose
/// parts are placed and whose meaning is to be put together from theirs.
struct Placement {
    part: usize,
    at_start: Where,
    at_end: Where,
    together: bool,
}

/// The meaning of the written pattern at `root`, with every anchor read as what it comes to, put in
/// `tree` beside it; or `None` where an anchor cannot be settled.
pub(crate) fn place_anchors(tree: &mut Tree, root: usize) -> Option<usize> {
    let known = facts_of(tree, root);
    let mut tasks = vec![Placement {
        part: root,
        at_start: Where::Yes,
        at_end: Where::Yes,
        together: false,
    }];
    let mut results: Vec<usize> = Vec::new();
    while let Some(task) = tasks.pop() {
        if task.together {
            put_together(tree, task.part, &mut results);
            continue;
        }
        let around = |part, at_start, at_end| Placement {
            part,
            at_start,
            at_end,
            together: false,
        };
        match &tree.parts[task.part] {
            Part::Nothing | Part::Never | Part::Symbols(_) => results.push(task.part),
            &Part::Anchor { end } => {
                let made = anchor(end, if end { task.at_end } else { task.at_start })?;
                results.push(tree.add(made));
            }
            Part::EitherOf(arms) => {
                // Every arm of a choice begins where the choice begins and ends where it ends.
                tasks.push(Placement {
                    together: true,
                    ..task
                });
                tasks.extend(
                    arms.iter()
                        .rev()
                        .map(|&arm| around(arm, task.at_start, task.at_end)),
                );
            }
            Part::InTurn(parts) => {
                let sides = sides_of(parts, &known, task.at_start, task.at_end);
                let parts: Vec<Placement> = parts
                    .iter()
                    .zip(sides)
                    .rev()
                    .map(|(&part, (at_start, at_end))| around(part, at_start, at_end))
                    .collect();
                tasks.push(Placement {
                    together: true,
                    ..task
                });
                tasks.extend(parts);
            }
            &Part::Repeated { body, least, most } => {
                if !known[body].holds {
                    tasks.push(Placement {
                        together: true,
                        ..task
                    });
                    tasks.push(around(body, task.at_start, task.at_end));
                } else if least == 1 && most == Some(1) {
                    // One copy is the thing itself and stands where the repetition stands.
                    tasks.push(around(body, task.at_start, task.at_end));
                } else {
                    // Any other count leaves how many copies come before the anchor to the string.
                    return None;
                }
            }
        }
    }
    results.pop()
}

/// What an anchor standing `at` comes to, or `None` where that cannot be settled. `^` asks to be at
/// the start of the string and there is one such place, so anything that must take a symbol before
/// it leaves no string at all. A `$` with something after it that must take a symbol is refused
/// rather than read the same way: the language keeps the set of patterns it reads, and that set has
/// no pattern of this shape.
fn anchor(end: bool, at: Where) -> Option<Part> {
    match at {
        Where::Yes => Some(Part::Nothing),
        Where::No if !end => Some(Part::Never),
        Where::No | Where::Unsettled => None,
    }
}

/// Takes the meanings of `part`'s parts off the end of `results`, and puts its meaning there.
fn put_together(tree: &mut Tree, part: usize, results: &mut Vec<usize>) {
    let made = match &tree.parts[part] {
        Part::EitherOf(arms) => {
            let cut = results.len() - arms.len();
            Part::EitherOf(results.split_off(cut))
        }
        Part::InTurn(parts) => {
            let cut = results.len() - parts.len();
            // An anchor that asks for nothing leaves nothing in the sequence, so ^abc$ means what abc
            // means and is the same tree.
            let mut parts: Vec<usize> = results.split_off(cut);
            parts.retain(|&made| !matches!(tree.parts[made], Part::Nothing));
            match parts.len() {
                0 => Part::Nothing,
                1 => {
                    results.push(parts[0]);
                    return;
                }
                _ => Part::InTurn(parts),
            }
        }
        &Part::Repeated { least, most, .. } => {
            let body = results
                .pop()
                .expect("a repetition's body is placed before it");
            Part::Repeated { body, least, most }
        }
        Part::Nothing | Part::Never | Part::Symbols(_) | Part::Anchor { .. } => {
            unreachable!("a leaf has no parts to put together")
        }
    };
    let made = tree.add(made);
    results.push(made);
}

/// Where each part of a sequence stands: at the start of the string where nothing before it takes a
/// symbol and the sequence is there, and not there where everything before it must; the same for
/// the end. What stands before each part and after it is gathered once from each end, so that a
/// literal written out a symbol at a time does not cost its length squared.
fn sides_of(
    parts: &[usize],
    known: &[Facts],
    at_start: Where,
    at_end: Where,
) -> Vec<(Where, Where)> {
    let count = parts.len();
    let (mut may_before, mut must_before) = (vec![false; count + 1], vec![true; count + 1]);
    for (at, &part) in parts.iter().enumerate() {
        may_before[at + 1] = may_before[at] || known[part].may;
        must_before[at + 1] = must_before[at] && known[part].must;
    }
    let (mut may_after, mut must_after) = (vec![false; count + 1], vec![true; count + 1]);
    for (at, &part) in parts.iter().enumerate().rev() {
        may_after[at] = may_after[at + 1] || known[part].may;
        must_after[at] = must_after[at + 1] && known[part].must;
    }
    (0..count)
        .map(|at| {
            (
                beyond(may_before[at], must_before[at], at_start),
                beyond(may_after[at + 1], must_after[at + 1], at_end),
            )
        })
        .collect()
}

/// Where a part stands, given what is on that side of it and where they all stand together. Nothing
/// on that side takes a symbol, so the part stands where they all do. Everything on that side must
/// take one, so it does not. Anything in between and the answer belongs to a string rather than to
/// the pattern.
fn beyond(any_takes: bool, all_take: bool, outer: Where) -> Where {
    if !any_takes {
        outer
    } else if all_take {
        Where::No
    } else {
        Where::Unsettled
    }
}

/// The facts of every part under `root`, by its place in the tree, worked out from the leaves up. A
/// part is pushed once to have its parts worked out and once more, below them, to be worked out from
/// theirs.
fn facts_of(tree: &Tree, root: usize) -> Vec<Facts> {
    let mut known = vec![Facts::default(); tree.parts.len()];
    let mut stack = vec![(root, false)];
    while let Some((part, parts_done)) = stack.pop() {
        let inside: &[usize] = match &tree.parts[part] {
            Part::InTurn(parts) | Part::EitherOf(parts) => parts,
            Part::Repeated { body, .. } => core::slice::from_ref(body),
            _ => &[],
        };
        if !parts_done && !inside.is_empty() {
            stack.push((part, true));
            stack.extend(inside.iter().map(|&inner| (inner, false)));
            continue;
        }
        known[part] = match &tree.parts[part] {
            // What the reader writes before the anchors are placed is a set of symbols, nothing, an
            // anchor or a shape of those.
            Part::Nothing => Facts::default(),
            Part::Symbols(_) => Facts {
                may: true,
                must: true,
                holds: false,
            },
            Part::Never => Facts {
                may: false,
                must: true,
                holds: false,
            },
            Part::Anchor { .. } => Facts {
                holds: true,
                ..Facts::default()
            },
            Part::InTurn(parts) => parts.iter().fold(Facts::default(), |f, &p| Facts {
                may: f.may || known[p].may,
                must: f.must || known[p].must,
                holds: f.holds || known[p].holds,
            }),
            Part::EitherOf(arms) => arms.iter().fold(
                Facts {
                    must: true,
                    ..Facts::default()
                },
                |f, &a| Facts {
                    may: f.may || known[a].may,
                    must: f.must && known[a].must,
                    holds: f.holds || known[a].holds,
                },
            ),
            &Part::Repeated { body, least, most } => Facts {
                may: most != Some(0) && known[body].may,
                must: least > 0 && known[body].must,
                holds: known[body].holds,
            },
        };
    }
    known
}
