//! The states a pattern comes to with its repetitions written out, counted from what is written and
//! without building anything. This is the measure [`PatternLimit::MachineStates`] is stated in, and it
//! is the specifications' and not this implementation's: every implementation counts the same number
//! from the same text. Counted on the pattern as written:
//!
//! - a character, an escape, `.`, a shorthand and a class are one each; so are `^` and `$`;
//! - an empty pattern, group or alternative is none;
//! - a sequence is the sum of its parts, and a group is what is inside it;
//! - a choice of n alternatives is one more than the sum of one more than each alternative;
//! - `A{n,m}` is m times A, plus one; `A{n}` is `A{n,n}` and `A?` is `A{0,1}`;
//! - `A{n,}` is n + 1 times A, plus one; `A*` is `A{0,}` and `A+` is `A{1,}`;
//! - the pattern is one more than what it is written as.
//!
//! For a pattern without anchors this is the states of the machine its shape builds, and an anchor
//! counts one and comes to at most one state, so a pattern within the limit always has a machine.
//! Counted up to one past the limit and no further, so a count as large as the reader reads is
//! multiplied without overflowing.
//!
//! Asked only of a pattern within the nesting depth, so the walk recurses.

use super::PatternLimit;
use super::tree::{Part, Tree};

/// One past the limit: every count above the limit is this.
const PAST: u64 = PatternLimit::MachineStates.most() as u64 + 1;

/// The states the written pattern at `root` comes to as a whole pattern, or one past the limit.
pub(crate) fn written_states(tree: &Tree, root: usize) -> u64 {
    plus(1, states_in(tree, root))
}

fn states_in(tree: &Tree, part: usize) -> u64 {
    match &tree.parts[part] {
        Part::Nothing => 0,
        Part::Symbols(_) | Part::Anchor { .. } => 1,
        Part::Never => unreachable!("the reader writes no part that is never"),
        Part::InTurn(parts) => parts
            .iter()
            .fold(0, |sum, &p| plus(sum, states_in(tree, p))),
        Part::EitherOf(arms) => arms
            .iter()
            .fold(1, |sum, &arm| plus(sum, plus(1, states_in(tree, arm)))),
        &Part::Repeated { body, least, most } => {
            let copies = most.map_or(u64::from(least) + 1, u64::from);
            plus(times(copies, states_in(tree, body)), 1)
        }
    }
}

fn plus(one: u64, other: u64) -> u64 {
    PAST.min(one + other)
}

fn times(copies: u64, body: u64) -> u64 {
    PAST.min(copies.saturating_mul(body))
}
