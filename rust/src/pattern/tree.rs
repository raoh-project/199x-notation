use alloc::vec::Vec;

use super::symbols::Symbols;

/// A pattern as its reader holds it, and then as what it means: the parts in one list, each naming
/// the parts inside it by their place in the list.
///
/// Text may nest groups as deeply as it is long, and is read to its end before the nesting depth is
/// asked, so the tree is held flat: dropping it, or walking it before the depth is known, takes no
/// stack of its own.
pub(crate) struct Tree {
    pub(crate) parts: Vec<Part>,
    /// The sets a [`Part::Symbols`] names, by their place here.
    pub(crate) sets: Vec<Symbols>,
}

/// One part of a pattern.
///
/// What is written is a set of symbols, an anchor, or parts in turn, either of them or repeated. What
/// it means is the same without the anchor, whose answer is settled by where it stands among the
/// rest of the pattern; an anchor nothing can satisfy leaves [`Part::Never`] behind. Whether a
/// repetition is greedy or reluctant and whether a group captures say what an engine does on the way
/// and not which strings come out, so neither is here.
pub(crate) enum Part {
    /// The one string of no symbols.
    Nothing,
    /// No string at all.
    Never,
    /// One symbol out of the set at this place in [`Tree::sets`].
    Symbols(usize),
    /// `^`, or `$` where `end`.
    Anchor { end: bool },
    /// The parts one after another, two or more.
    InTurn(Vec<usize>),
    /// Any one of the arms, two or more.
    EitherOf(Vec<usize>),
    /// The same part from `least` times to `most`, or with no ceiling where `most` is `None`.
    Repeated {
        body: usize,
        least: u32,
        most: Option<u32>,
    },
}

impl Tree {
    pub(crate) fn new() -> Tree {
        Tree {
            parts: Vec::new(),
            sets: Vec::new(),
        }
    }

    /// Puts `part` in the tree, and answers its place.
    pub(crate) fn add(&mut self, part: Part) -> usize {
        self.parts.push(part);
        self.parts.len() - 1
    }

    /// Puts a part of one symbol out of `set` in the tree.
    pub(crate) fn symbols(&mut self, set: Symbols) -> usize {
        self.sets.push(set);
        let at = self.sets.len() - 1;
        self.add(Part::Symbols(at))
    }
}
