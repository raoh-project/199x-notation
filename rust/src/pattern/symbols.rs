use alloc::vec;
use alloc::vec::Vec;

/// A set of the characters a string is made of: Unicode scalar values, every code point but the
/// surrogates.
///
/// The universe is what text can hold, so the complement is taken within the scalar values and no
/// set names a surrogate. Held as runs, sorted, apart and never touching, so that one set has one
/// spelling. A literal is one code point, a class is a union of runs, a negated class is the universe
/// less that union, and `.` is the universe less the five line terminators: the same algebra, so
/// nothing downstream needs to know which shape a set came from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Symbols(Vec<(char, char)>);

impl Symbols {
    /// The set of `c` alone.
    pub(crate) fn one(c: char) -> Symbols {
        Symbols(vec![(c, c)])
    }

    /// Every scalar value from `first` to `last`, both ends in it, the surrogates left out, which a
    /// `char` cannot be.
    pub(crate) fn between(first: char, last: char) -> Symbols {
        Symbols::normalized(vec![(first, last)])
    }

    /// Every scalar value there is.
    pub(crate) fn everything() -> Symbols {
        Symbols::between(char::MIN, char::MAX)
    }

    /// The symbols in any of `sets`.
    pub(crate) fn union_of(sets: &[Symbols]) -> Symbols {
        Symbols::normalized(sets.iter().flat_map(|set| set.0.iter().copied()).collect())
    }

    /// `runs` sorted and joined where they touch or overlap. Two runs on either side of the
    /// surrogates touch, since nothing a set can hold is between them.
    pub(crate) fn normalized(mut runs: Vec<(char, char)>) -> Symbols {
        runs.sort_unstable();
        let mut out: Vec<(char, char)> = Vec::with_capacity(runs.len());
        for (first, last) in runs {
            if let Some(previous) = out.last_mut()
                && after(previous.1).is_none_or(|next| next >= first)
            {
                previous.1 = previous.1.max(last);
                continue;
            }
            out.push((first, last));
        }
        Symbols(out)
    }

    /// Every scalar value these do not hold.
    pub(crate) fn not(&self) -> Symbols {
        let mut out = Vec::new();
        let mut next = Some(char::MIN);
        for &(first, last) in &self.0 {
            if let Some(from) = next
                && from < first
            {
                out.push((from, before(first)));
            }
            next = after(last);
        }
        if let Some(from) = next {
            out.push((from, char::MAX));
        }
        Symbols(out)
    }

    /// These without `those`.
    pub(crate) fn less(&self, those: &Symbols) -> Symbols {
        Symbols::union_of(&[self.not(), those.clone()]).not()
    }

    /// The set of `runs`, which are in order and apart, as an image writes them: two may be next to
    /// each other, and are kept as two.
    pub(crate) fn in_order(runs: Vec<(char, char)>) -> Symbols {
        Symbols(runs)
    }

    /// Whether any scalar value from `first` to `last` is one of these.
    pub(crate) fn meets(&self, first: char, last: char) -> bool {
        let at = self.0.partition_point(|&(_, end)| end < first);
        at < self.0.len() && self.0[at].0 <= last
    }

    /// Whether `c` is one of these.
    pub(crate) fn has(&self, c: char) -> bool {
        crate::tables::within(&self.0, c)
    }

    /// The one symbol these hold, where they hold one.
    pub(crate) fn single(&self) -> Option<char> {
        match self.0[..] {
            [(first, last)] if first == last => Some(first),
            _ => None,
        }
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub(crate) fn runs(&self) -> &[(char, char)] {
        &self.0
    }
}

/// The scalar value before `c`, which is not U+0000.
fn before(c: char) -> char {
    let below = u32::from(c) - 1;
    char::from_u32(below).unwrap_or('\u{D7FF}')
}

/// The scalar value after `c`, or `None` past the last.
fn after(c: char) -> Option<char> {
    if c == char::MAX {
        None
    } else {
        Some(char::from_u32(u32::from(c) + 1).unwrap_or('\u{E000}'))
    }
}

/// The sets a pattern writes with a shorthand or a dot.
pub(crate) struct Shorthands {
    /// `.`: every symbol but a line feed, a carriage return, the next-line character and the two
    /// separators.
    pub(crate) dot: Symbols,
    /// `\d`: the ten ASCII digits and no other.
    pub(crate) digit: Symbols,
    /// `\w`: the ASCII letters, the ASCII digits and the underscore.
    pub(crate) word: Symbols,
    /// `\s`: a space, a tab, a line feed, a vertical tab, a form feed and a carriage return. Not the
    /// `White_Space` set, which the specifications state separately.
    pub(crate) space: Symbols,
}

impl Shorthands {
    pub(crate) fn new() -> Shorthands {
        let line_terminators = Symbols::union_of(&[
            Symbols::one('\n'),
            Symbols::one('\r'),
            Symbols::one('\u{85}'),
            Symbols::one('\u{2028}'),
            Symbols::one('\u{2029}'),
        ]);
        let digit = Symbols::between('0', '9');
        Shorthands {
            dot: Symbols::everything().less(&line_terminators),
            word: Symbols::union_of(&[
                Symbols::between('a', 'z'),
                Symbols::between('A', 'Z'),
                digit.clone(),
                Symbols::one('_'),
            ]),
            digit,
            space: Symbols::union_of(&[Symbols::one(' '), Symbols::between('\t', '\r')]),
        }
    }
}
