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
        Machine {
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

    fn free_from(&self, q: usize) -> &[u32] {
        &self.free[self.free_starts[q] as usize..self.free_starts[q + 1] as usize]
    }

    /// Whether the whole of `subject` is accepted.
    ///
    /// Every state the machine may be in is walked at once, a scalar value at a time, and nothing is
    /// gone back over, so a match takes time linear in the subject: each scalar value costs the steps
    /// out of the states the walk is in and the free steps from where they lead, at most the
    /// machine's. Each state is entered at most once at each scalar value, which the generation it
    /// was last entered in says. Every step of a match is taken in this loop.
    pub(crate) fn matches(&self, subject: &str) -> bool {
        let mut walk = Walk {
            entered: vec![0; self.states()],
            generation: 1,
            pending: Vec::new(),
        };
        let mut now = Vec::new();
        let mut next = Vec::new();
        walk.enter(self, &mut now, 0);
        for c in subject.chars() {
            walk.next_generation();
            next.clear();
            for &q in &now {
                for &(set, to) in self.steps_from(q as usize) {
                    if self.sets[set as usize].has(c) {
                        walk.enter(self, &mut next, to);
                    }
                }
            }
            core::mem::swap(&mut now, &mut next);
            if now.is_empty() {
                return false;
            }
        }
        now.iter().any(|&q| self.accepting[q as usize])
    }
}

/// The room one match works in: the generation each state was last entered in, and the states the
/// free steps are still to be followed from.
struct Walk {
    entered: Vec<u32>,
    generation: u32,
    pending: Vec<u32>,
}

impl Walk {
    fn next_generation(&mut self) {
        if self.generation == u32::MAX {
            self.entered.fill(0);
            self.generation = 0;
        }
        self.generation += 1;
    }

    /// Puts `q` in `into`, with every state the free steps reach from it, each once.
    fn enter(&mut self, machine: &Machine, into: &mut Vec<u32>, q: u32) {
        if self.entered[q as usize] == self.generation {
            return;
        }
        self.entered[q as usize] = self.generation;
        into.push(q);
        self.pending.push(q);
        while let Some(from) = self.pending.pop() {
            for &to in machine.free_from(from as usize) {
                if self.entered[to as usize] != self.generation {
                    self.entered[to as usize] = self.generation;
                    into.push(to);
                    self.pending.push(to);
                }
            }
        }
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
    /// copies each of which may be stepped over, and an unbounded ceiling is one more copy with a
    /// free step back to where it began.
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
        for _ in 0..least {
            at = self.build(body, at);
        }
        let Some(most) = most else {
            let back = self.state();
            self.freely(at, back);
            let exit = self.build(body, back);
            self.freely(exit, back);
            return back;
        };
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
