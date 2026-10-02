use alloc::boxed::Box;
use alloc::vec;
use alloc::vec::Vec;

use super::symbols::Symbols;
use super::tree::{Part, Tree};

/// The strings a pattern accepts, as states to walk between: steps that cost a symbol out of a set,
/// and free steps that cost nothing. A walk starts at state 0 and accepts where it ends at a state
/// that accepts.
///
/// This is the one shape a match runs, whether the machine was built from a pattern or read from an
/// image, so it holds whatever an image may write: any number of states that accept, sets that hold
/// the same runs or that no step is over, states no walk reaches, and the steps of a state in any
/// order. Nothing about a match leans on how this crate's own builder lays a machine out.
pub(crate) struct Machine {
    pub(crate) sets: Vec<Symbols>,
    pub(crate) accepting: Vec<bool>,
    /// The steps out of state `q` are `steps[step_starts[q]..step_starts[q + 1]]`, each the set it is
    /// over and the state it leads to.
    step_starts: Vec<u32>,
    steps: Vec<(u32, u32)>,
    /// The free steps out of state `q`, the same way.
    free_starts: Vec<u32>,
    free: Vec<u32>,
    /// The classes of characters no set tells apart.
    pub(crate) classes: Classes,
}

impl Machine {
    /// The machine of `accepting.len()` states with these steps, each `(from, set, to)`, and free
    /// steps, each `(from, to)`, given in any order. Every state and set they name is one the machine
    /// has.
    pub(crate) fn new(
        sets: Vec<Symbols>,
        accepting: Vec<bool>,
        steps: &[(u32, u32, u32)],
        free: &[(u32, u32)],
    ) -> Machine {
        let states = accepting.len();
        let (step_starts, steps) = by_state(
            states,
            steps.iter().map(|&(from, set, to)| (from, (set, to))),
        );
        let (free_starts, free) = by_state(states, free.iter().copied());
        let classes = Classes::of(&sets);
        Machine {
            classes,
            sets,
            accepting,
            step_starts,
            steps,
            free_starts,
            free,
        }
    }

    pub(crate) fn states(&self) -> usize {
        self.accepting.len()
    }

    /// The steps out of `q`, each the set it is over and the state it leads to.
    pub(crate) fn steps_from(&self, q: usize) -> &[(u32, u32)] {
        &self.steps[self.step_starts[q] as usize..self.step_starts[q + 1] as usize]
    }

    pub(crate) fn free_from(&self, q: usize) -> &[u32] {
        &self.free[self.free_starts[q] as usize..self.free_starts[q + 1] as usize]
    }
}

/// The scalar values cut where any set of a machine begins or ends, so that two characters of one
/// class are in the same sets: a step is over the whole of a class or none of it. A walk asks where a
/// class leads rather than a character, so what it keeps is kept for every character of the class.
pub(crate) struct Classes {
    /// Class `k` is the scalar values from `starts[k]` to the one before `starts[k + 1]`, or to the
    /// last where it is the last class. No class begins at a surrogate.
    starts: Vec<u32>,
    /// The class of each ASCII character, by its byte. A byte past ASCII is the first of a character
    /// of two bytes or more, whose class is found by searching `starts`; its place here is 0 and is
    /// never read, and is here so that a byte looks its class up with no check of its own.
    ascii: Box<[u32; 256]>,
}

impl Classes {
    fn of(sets: &[Symbols]) -> Classes {
        // Where a run ends, the next scalar value begins a class; one past the surrogates is where a
        // class begins that would otherwise begin among them.
        let mut starts: Vec<u32> = vec![0];
        for set in sets {
            for &(first, last) in set.runs() {
                starts.push(u32::from(first));
                starts.push(u32::from(last) + 1);
            }
        }
        for start in &mut starts {
            if (0xD800..=0xDFFF).contains(start) {
                *start = 0xE000;
            }
        }
        starts.retain(|start| *start <= u32::from(char::MAX));
        starts.sort_unstable();
        starts.dedup();
        let mut classes = Classes {
            starts,
            ascii: Box::new([0; 256]),
        };
        for c in 0..128u8 {
            classes.ascii[usize::from(c)] = classes.class_of(char::from(c));
        }
        classes
    }

    /// The class of each ASCII character, by its byte; the places of the bytes past ASCII are not
    /// classes.
    pub(crate) fn ascii(&self) -> &[u32; 256] {
        &self.ascii
    }

    pub(crate) fn count(&self) -> usize {
        self.starts.len()
    }

    /// The class of `c`.
    pub(crate) fn class(&self, c: char) -> u32 {
        if c.is_ascii() {
            self.ascii[c as usize]
        } else {
            self.class_of(c)
        }
    }

    fn class_of(&self, c: char) -> u32 {
        (self.starts.partition_point(|start| *start <= u32::from(c)) - 1) as u32
    }
}

/// `items`, each named by the state it is out of, grouped by that state: where each state's begin,
/// and the items in that order.
fn by_state<T: Copy + Default>(
    states: usize,
    items: impl Iterator<Item = (u32, T)> + Clone,
) -> (Vec<u32>, Vec<T>) {
    let mut starts = vec![0u32; states + 1];
    for (from, _) in items.clone() {
        starts[from as usize + 1] += 1;
    }
    for q in 0..states {
        starts[q + 1] += starts[q];
    }
    let mut placed = starts.clone();
    let mut out = vec![T::default(); starts[states] as usize];
    for (from, item) in items {
        out[placed[from as usize] as usize] = item;
        placed[from as usize] += 1;
    }
    (starts, out)
}

/// The machine the shape of a pattern's meaning builds. A choice is a free step into either arm and
/// a repetition a free step back to where it started, so the machine has no more states than the
/// pattern is counted at, which is within [`super::PatternLimit::MachineStates`].
pub(crate) fn build(tree: Tree, meaning: usize) -> Machine {
    let mut builder = Builder {
        tree: &tree,
        accepting: Vec::new(),
        steps: Vec::new(),
        free: Vec::new(),
    };
    let start = builder.state();
    let accept = builder.build(meaning, start);
    builder.accepting[accept as usize] = true;
    let Builder {
        accepting,
        steps,
        free,
        ..
    } = builder;
    Machine::new(tree.sets, accepting, &steps, &free)
}

struct Builder<'a> {
    tree: &'a Tree,
    accepting: Vec<bool>,
    steps: Vec<(u32, u32, u32)>,
    free: Vec<(u32, u32)>,
}

impl Builder<'_> {
    fn state(&mut self) -> u32 {
        self.accepting.push(false);
        (self.accepting.len() - 1) as u32
    }

    fn freely(&mut self, from: u32, to: u32) {
        self.free.push((from, to));
    }

    /// Makes the states for `part`, walked into from `from`, and answers where it leaves off: one
    /// entry and one exit apiece, which is what lets the shapes compose without any of them knowing
    /// what it is inside. Recursive, since a pattern that was read nests no deeper than the nesting
    /// depth.
    fn build(&mut self, part: usize, from: u32) -> u32 {
        match &self.tree.parts[part] {
            Part::Nothing => from,
            // Nothing leads out of it, so nothing after it is reached.
            Part::Never => self.state(),
            &Part::Symbols(set) => {
                let to = self.state();
                self.steps.push((from, set as u32, to));
                to
            }
            Part::InTurn(parts) => parts.iter().fold(from, |at, &inner| self.build(inner, at)),
            Part::EitherOf(arms) => {
                let out = self.state();
                for &arm in arms {
                    let into = self.state();
                    self.freely(from, into);
                    let exit = self.build(arm, into);
                    self.freely(exit, out);
                }
                out
            }
            &Part::Repeated { body, least, most } => self.repeated(body, least, most, from),
            Part::Anchor { .. } => unreachable!("an anchor is placed before a machine is built"),
        }
    }

    /// A repetition as the copies it is: the floor is copies one after another, what is above it is
    /// copies each of which may be stepped over, and an unbounded ceiling is a copy with a free step
    /// back to where it began.
    ///
    /// With no ceiling, the last copy of the floor is that copy, and is left by its end: `X+` is one
    /// copy of X, entered once and again as often as the string asks, and `X*` is the same entered
    /// from a state the walk may also leave by. So a walk that has read the first copy is where one
    /// that has read the tenth is, and the sets of states a match keeps do not tell the two apart.
    fn repeated(&mut self, body: usize, least: u32, most: Option<u32>, from: u32) -> u32 {
        // A body that makes no state is the empty string however many times it is taken, and is
        // built as that: one state to end in. Copied a count at a time it would cost the count and
        // make nothing.
        if self.builds_no_state(body) {
            let out = self.state();
            self.freely(from, out);
            return out;
        }
        let mut at = from;
        let Some(most) = most else {
            for _ in 1..least {
                at = self.build(body, at);
            }
            let back = self.state();
            self.freely(at, back);
            let exit = self.build(body, back);
            self.freely(exit, back);
            return if least == 0 { back } else { exit };
        };
        for _ in 0..least {
            at = self.build(body, at);
        }
        let out = self.state();
        self.freely(at, out);
        for _ in least..most {
            at = self.build(body, at);
            self.freely(at, out);
        }
        out
    }

    /// Whether building `part` makes no state, which is only ever the empty string.
    fn builds_no_state(&self, part: usize) -> bool {
        match &self.tree.parts[part] {
            Part::Nothing => true,
            Part::InTurn(parts) => parts.iter().all(|&inner| self.builds_no_state(inner)),
            _ => false,
        }
    }
}
