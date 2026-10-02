//! The pattern language: reading a pattern, the limits every implementation holds a pattern to, and
//! the strings a pattern accepts.

mod anchors;
mod image;
mod machine;
mod read;
mod states;
mod symbols;
mod tree;

use alloc::string::{String, ToString};

pub use image::NotAnImage;
use machine::Machine;
use read::Reader;

/// What came of reading a pattern.
///
/// The three answers are about three different things. A [`Pattern`] is a pattern of the language
/// within the limits every implementation holds to. [`PatternRefused`] is text that is no pattern of
/// the language, and says what in it is not. [`PatternBeyond`] is a pattern of the language written
/// past one of those limits: every construct in it is one the language has, so an author told it is
/// not in the language would go looking for a construct that is not there.
///
/// A pattern read in part is not an answer: a tree of the constructs that were understood accepts a
/// language the author did not write.
#[derive(Debug)]
pub enum PatternRead {
    /// A pattern within the limits.
    Pattern(Pattern),
    /// Text that is no pattern.
    Refused(PatternRefused),
    /// A pattern past a limit.
    Beyond(PatternBeyond),
}

/// A pattern that was read, as the strings it accepts.
pub struct Pattern {
    machine: Machine,
}

impl Pattern {
    /// Whether the whole of `subject` is one of the strings the pattern accepts.
    ///
    /// The subject is read a scalar value at a time, once, and never gone back over: the machine the
    /// pattern means is walked as the set of states it may be in, so for a pattern a match takes
    /// time linear in the subject. The machine is built from the shape of the pattern, its
    /// repetitions written out, and is not made deterministic, so each scalar value may cost as many
    /// steps as the machine has states, which for a pattern read from text is at most 250,000.
    pub fn matches(&self, subject: &str) -> bool {
        self.machine.matches(subject)
    }

    /// The pattern `image` is an image of, in the format P1 that `image/P1.md` in the repository
    /// defines, or [`NotAnImage`] where it is not one: every rule of the format is asked before
    /// anything is matched.
    ///
    /// The image is the machine another implementation built, written out so that it runs here, and
    /// it accepts what the pattern it was written from accepts. It is held to the rules of the format
    /// and not to the limits a pattern read from text is held to: a reader reads every image a writer
    /// writes, and may read a longer one. A match walks the machine as it is written, as the set of
    /// states it may be in, so each scalar value of the subject may cost as many steps as the image
    /// has.
    ///
    /// Reading is not bounded by the image's length where the image says its machine is
    /// deterministic. That no two steps out of one state are over sets with a scalar value in common
    /// is asked once for each different group of sets a state steps over, and a set is written once
    /// however many groups hold it, so in the worst case the work grows with the square of the
    /// image's length. Java holds an image to the same rule the same way.
    ///
    /// The crate writes no image.
    pub fn from_image(image: &str) -> Result<Pattern, NotAnImage> {
        image::read(image).map(|machine| Pattern { machine })
    }
}

impl core::fmt::Debug for Pattern {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("Pattern").finish_non_exhaustive()
    }
}

/// Text that is no pattern of the language, and what stopped the reading.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PatternRefused {
    /// Which kind of thing it is.
    pub why: PatternRefusal,
    /// Where in the text the construct that stopped the reading begins, in bytes.
    pub from: usize,
    /// The construct as written, which is empty where the text ended before a construct it had
    /// begun was whole.
    pub construct: String,
}

/// A pattern of the language written past one of the limits every implementation holds to.
///
/// Not a refusal: the language has no count, depth or size past which a pattern stops being one.
/// What is past a limit is what no implementation is asked to run. Answered only of text read to its
/// end and found to be a pattern, its anchors placed: text that is no pattern is [`PatternRefused`],
/// whatever limit it also went past.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PatternBeyond {
    /// Which limit it is past.
    pub limit: PatternLimit,
    /// Where in the text the construct that is past it begins, in bytes; nought for
    /// [`PatternLimit::MachineStates`], which is a fact about the whole pattern.
    pub from: usize,
    /// The construct as written: the count, the group opened past the depth, or the whole pattern.
    pub construct: String,
}

/// One of the limits on a pattern every implementation holds to, each the same number everywhere.
/// They bound what running a pattern costs, and are stated of the text so that no implementation's
/// way of running one decides which patterns it takes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PatternLimit {
    /// A count of a repetition, written in `{n}`, `{n,}` or `{n,m}`: at most 134,217,727.
    RepetitionCount,
    /// Groups one inside another: at most 200.
    NestingDepth,
    /// The states of the pattern with its repetitions written out, counted from the text without
    /// building anything: at most 250,000.
    MachineStates,
}

impl PatternLimit {
    /// The greatest count, depth or number of states within the limit.
    pub const fn most(self) -> usize {
        match self {
            PatternLimit::RepetitionCount => 134_217_727,
            PatternLimit::NestingDepth => 200,
            PatternLimit::MachineStates => 250_000,
        }
    }
}

/// What makes text no pattern of the language, told apart by what an author wrote. The first three
/// are text that is no pattern at all. The rest are text that would be a pattern in some other
/// language and is not one in this, each for a reason of its own.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PatternRefusal {
    /// A bracket, brace or parenthesis with nothing closing it, a class with nothing in it, or a
    /// repetition with nothing before it to repeat.
    SomethingUnclosed,
    /// A repetition whose count is no count: one with no digits, a ceiling below its floor, or a run
    /// whose end comes before its start. A count past [`PatternLimit::RepetitionCount`] is a count,
    /// and is [`PatternBeyond`].
    ACountThisCannotRead,
    /// An escape with no meaning, or one with nothing after it.
    AnEscapeThisDoesNotRead,
    /// A character no text holds: half of a surrogate pair written by its number, `\uD800` on its own
    /// or `\x{DC00}`.
    ACharacterNoStringHolds,
    /// A group beginning `(?` other than `(?:`: a lookahead, a lookbehind, a named group, a flag
    /// group.
    AGroupTheGrammarDoesNotHave,
    /// A reference back to what another part of the pattern matched, which can denote a set no
    /// regular language is.
    ABackReference,
    /// A property of a character, `\p{Alpha}` or `\P{...}`. The language names symbols by their
    /// numbers and has nothing to ask a property with.
    ACharacterProperty,
    /// `\b`, `\B`, `\A`, `\z`, `\Z`, `\G` or `\R`. The grammar has `^` and `$` for the ends and
    /// nothing else that stands between characters.
    ABoundary,
    /// `\Q ... \E`, which turns off the reading of what is inside it.
    AQuotation,
    /// A class inside a class, or classes joined by `&&`.
    AClassOfClasses,
    /// `++`, `*+` and the rest: a repetition that gives nothing back, whose strings follow from how
    /// a matcher walks, which the language does not describe.
    APossessiveRepetition,
    /// An anchor whose answer is not a property of the pattern, as in `(a|)^b`, where which strings
    /// are accepted is settled by which arm a string took.
    AnAnchorThisCannotPlace,
}

/// What `text` means as a pattern, or what makes it no pattern, or which limit it is past.
///
/// A limit is about a pattern, so it is answered only once the text is known to be one: a count or
/// a depth past its limit is noted where it is met and the reading goes on to the end, and text that
/// is no pattern anywhere in it is [`PatternRefused`] whatever limit it also went past. Of the
/// limits, the first one met in the text, left to right, is the answer, and the states are counted
/// last, of a pattern within the other two, before any machine is built.
///
/// The reading has no depth of its own: groups are read with a stack rather than by recursion, and
/// the anchors are placed the same way, so text nested as deeply as it is long is read to its end.
pub fn read_pattern(text: &str) -> PatternRead {
    let mut reader = Reader::new(text);
    let written = match reader.pattern() {
        Ok(written) => written,
        Err(refusal) => {
            let to = refusal.to.max(refusal.from).min(text.len());
            return PatternRead::Refused(PatternRefused {
                why: refusal.why,
                from: refusal.from,
                construct: text[refusal.from..to].to_string(),
            });
        }
    };
    let mut tree = reader.tree;
    // Every anchor has to come to something, and what it comes to is settled by where it stands,
    // which is known now that the whole of the pattern is.
    let Some(meaning) = anchors::place_anchors(&mut tree, written) else {
        return PatternRead::Refused(PatternRefused {
            why: PatternRefusal::AnAnchorThisCannotPlace,
            from: 0,
            construct: text.to_string(),
        });
    };
    // The text is a pattern. Whether it is one every implementation takes is asked now.
    if let Some(past) = reader.past {
        return PatternRead::Beyond(past);
    }
    // Counted on what was written, where an anchor is one state whatever it came to, so the count is
    // never below the states of the machine the meaning builds.
    if states::written_states(&tree, written) > PatternLimit::MachineStates.most() as u64 {
        return PatternRead::Beyond(PatternBeyond {
            limit: PatternLimit::MachineStates,
            from: 0,
            construct: text.to_string(),
        });
    }
    PatternRead::Pattern(Pattern {
        machine: machine::build(tree, meaning),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The machine a pattern's shape builds has no more states than the pattern is counted at, so a
    /// pattern within the limit has a machine within it.
    #[test]
    fn a_machine_has_no_more_states_than_its_pattern_is_counted_at() {
        for text in [
            "",
            "a",
            "abc",
            "a|b|",
            "(a|b)*c",
            "a{3,5}",
            "(?:ab){2,}",
            "(?:)*",
            "(?:(?:)|a){4}",
            "^a$",
            "a^",
            "(?:a|^b)",
            "[^a]?.\\d",
            "(?:a?){0,7}b+",
        ] {
            let mut reader = Reader::new(text);
            let written = reader
                .pattern()
                .unwrap_or_else(|_| panic!("{text} is refused"));
            let mut tree = reader.tree;
            let counted = states::written_states(&tree, written);
            let meaning = anchors::place_anchors(&mut tree, written)
                .unwrap_or_else(|| panic!("{text}: no anchor placed"));
            let built = machine::build(tree, meaning).states() as u64;
            assert!(built <= counted, "{text}: built {built}, counted {counted}");
        }
    }
}
