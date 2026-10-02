use alloc::vec;
use alloc::vec::Vec;

use super::machine::{Classes, Machine};

/// About how many bytes one cache keeps of the sets it has worked out, before it forgets them and
/// starts again.
const KNOWN_BYTES: usize = 2 << 20;

/// Where a class leads from a kept set before that has been worked out.
const UNKNOWN: u32 = u32::MAX;
/// The kept set no state is in, from which no string is accepted.
const NONE: u32 = u32::MAX - 1;
/// A slot of [`Cache::slots`] no kept set is in.
const EMPTY: u32 = u32::MAX;

/// What matches against one machine keep between them: the sets of states a walk has been in, and
/// where each class of characters leads from them. These are the sets a deterministic machine would
/// have, made only as a walk comes to them and only as many as fit in [`KNOWN_BYTES`]; which sets are
/// kept, and whether any are, changes how fast a match is and no answer.
///
/// Where they would be more, they are forgotten and worked out again as they are come to. Where what
/// was worked out since they were last forgotten was looked up again less than once in ten, keeping
/// them saves nothing: the walk goes on without them, a state at a time, and so does every later
/// match with this cache. A machine whose sets are large, or subjects that keep coming to new ones,
/// are walked a state at a time as without them.
///
/// A set is the same set in whatever order its states were come to, so it is found by a hash that
/// does not turn on the order, summed as its states are entered ([`Walk::enter`]), and told apart
/// from another of the same hash by asking each of its states whether the walk has just entered it
/// ([`Cache::same`]). Nothing here puts a set in order, and nothing goes over a set but those loops.
pub(crate) struct Cache {
    /// Each kept set's states, in the order the walk came to them, every free step already taken.
    sets: Vec<Vec<u32>>,
    /// Each kept set's hash, and whether a walk that ends in it accepts.
    hashes: Vec<u32>,
    accepting: Vec<bool>,
    /// The kept sets by their hashes, each slot a set's place in `sets` or [`EMPTY`], looked for
    /// from the slot the hash names to the first empty one. Never more than half full.
    slots: Vec<u32>,
    /// Where class `k` leads from the kept set whose row begins at `s` is `next[s + k]`: the place
    /// the row of the set it leads to begins, [`NONE`], or [`UNKNOWN`]. The set whose row begins at
    /// `s` is `sets[s / classes]`.
    next: Vec<u32>,
    /// The kept set a walk starts in, where it is kept.
    start: Option<u32>,
    bytes: usize,
    /// The sets made and the bytes read since they were last forgotten. What is read is counted
    /// across every match with this cache, so it stops at the most a `usize` holds rather than
    /// going round; past there it is still more than ten for each set made.
    made: usize,
    read: usize,
    /// Whether keeping sets was given up on.
    off: bool,
    walk: Walk,
}

impl Cache {
    pub(crate) fn new() -> Cache {
        Cache {
            sets: Vec::new(),
            hashes: Vec::new(),
            accepting: Vec::new(),
            slots: Vec::new(),
            next: Vec::new(),
            start: None,
            bytes: 0,
            made: 0,
            read: 0,
            off: false,
            walk: Walk {
                entered: Vec::new(),
                generation: 0,
                pending: Vec::new(),
                now: Vec::new(),
                next: Vec::new(),
                hash: 0,
                accepting: false,
            },
        }
    }

    /// Forgets every kept set. What is dropped is at most what [`KNOWN_BYTES`] holds.
    fn forget(&mut self) {
        self.sets.clear();
        self.hashes.clear();
        self.accepting.clear();
        self.slots.clear();
        self.next.clear();
        self.start = None;
        self.bytes = 0;
        self.made = 0;
        self.read = 0;
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins: found
    /// where it is kept, and otherwise kept now, the set moved out of the walk. `None` where keeping
    /// sets is given up on, and the set is still in `walk.next`. The second answer is whether the sets
    /// kept before were forgotten to make room for it.
    fn keep(&mut self, classes: usize) -> Option<(u32, bool)> {
        if self.walk.next.is_empty() {
            return Some((NONE, false));
        }
        let hash = self.walk.hash;
        if !self.slots.is_empty() {
            let mask = self.slots.len() - 1;
            let mut at = hash as usize & mask;
            while self.slots[at] != EMPTY {
                let kept = self.slots[at] as usize;
                if self.hashes[kept] == hash && Cache::same(&self.sets[kept], &self.walk) {
                    return Some(((kept * classes) as u32, false));
                }
                at = (at + 1) & mask;
            }
        }
        let cost = classes * 4 + self.walk.next.len() * 4 + 64;
        let mut forgot = false;
        if self.bytes + cost > KNOWN_BYTES {
            let gave_up = self.read < 10 * self.made || cost > KNOWN_BYTES;
            self.forget();
            if gave_up {
                self.off = true;
                return None;
            }
            forgot = true;
        }
        let kept = self.sets.len();
        if (kept + 1) * 2 > self.slots.len() {
            self.grow();
        }
        let mask = self.slots.len() - 1;
        let mut at = hash as usize & mask;
        while self.slots[at] != EMPTY {
            at = (at + 1) & mask;
        }
        self.slots[at] = kept as u32;
        // A kept set is named by where its row of `next` begins, so a step is one lookup and no
        // product.
        let row = self.next.len() as u32;
        for _ in 0..classes {
            self.next.push(UNKNOWN);
        }
        self.sets.push(core::mem::take(&mut self.walk.next));
        self.hashes.push(hash);
        self.accepting.push(self.walk.accepting);
        self.bytes += cost;
        self.made += 1;
        Some((row, forgot))
    }

    /// Whether `held` is the set the walk has just entered: as many states, each entered in the
    /// walk's generation, asked one at a time.
    fn same(held: &[u32], walk: &Walk) -> bool {
        if held.len() != walk.next.len() {
            return false;
        }
        for &q in held {
            if walk.entered[q as usize] != walk.generation {
                return false;
            }
        }
        true
    }

    /// Twice the slots, or sixteen, each kept set put in again by its hash. What is gone over is the
    /// kept sets, at most what [`KNOWN_BYTES`] holds.
    fn grow(&mut self) {
        let size = (self.slots.len() * 2).max(16);
        self.slots.clear();
        for _ in 0..size {
            self.slots.push(EMPTY);
        }
        let mask = size - 1;
        for kept in 0..self.hashes.len() {
            let mut at = self.hashes[kept] as usize & mask;
            while self.slots[at] != EMPTY {
                at = (at + 1) & mask;
            }
            self.slots[at] = kept as u32;
        }
    }
}

/// A state's part of the hash of a set it is in, which is the same in whatever order the set's
/// states are put in.
fn scatter(q: u32) -> u32 {
    let mixed = q.wrapping_mul(0x9E37_79B9);
    mixed ^ (mixed >> 15)
}

/// The room a walk a state at a time works in: the generation each state was last entered in, the
/// states the free steps are still to be followed from, the set the walk is in and the one it is
/// coming to, and of that one, the hash and whether it accepts, summed as it is entered.
struct Walk {
    entered: Vec<u32>,
    generation: u32,
    pending: Vec<u32>,
    now: Vec<u32>,
    next: Vec<u32>,
    hash: u32,
    accepting: bool,
}

impl Walk {
    /// Starts the set the walk comes to next, in a generation of its own.
    fn next_set(&mut self, machine: &Machine) {
        if self.entered.is_empty() {
            self.entered = vec![0; machine.states()];
        }
        if self.generation == u32::MAX {
            // A loop of its own and not `fill`: it goes over every state of the machine, and is one a
            // checkpoint asks in.
            #[allow(clippy::manual_slice_fill)]
            for each in &mut self.entered {
                *each = 0;
            }
            self.generation = 0;
        }
        self.generation += 1;
        self.next.clear();
        self.hash = 0;
        self.accepting = false;
    }

    /// Puts `q` in `next`, with every state the free steps reach from it, each once.
    fn enter(&mut self, machine: &Machine, q: u32) {
        let add = |walk: &mut Walk, q: u32| {
            if walk.entered[q as usize] == walk.generation {
                return false;
            }
            walk.entered[q as usize] = walk.generation;
            walk.next.push(q);
            walk.hash = walk.hash.wrapping_add(scatter(q));
            walk.accepting |= machine.accepting[q as usize];
            true
        };
        if !add(self, q) {
            return;
        }
        self.pending.push(q);
        while let Some(from) = self.pending.pop() {
            for &to in machine.free_from(from as usize) {
                if add(self, to) {
                    self.pending.push(to);
                }
            }
        }
    }

    /// The states the walk starts in, in `next`.
    fn begin(&mut self, machine: &Machine) {
        self.next_set(machine);
        self.enter(machine, 0);
    }

    /// The states `from` leads to over `c`, in `next`: from each state, each step over it, and the
    /// states the free steps reach from where those lead. It is the one place states are moved, and
    /// its work is the steps out of the set and the states it comes to, at most the machine's.
    fn advance(&mut self, machine: &Machine, from: &[u32], c: char) {
        self.next_set(machine);
        for &q in from {
            for &(set, to) in machine.steps_from(q as usize) {
                if machine.sets[set as usize].has(c) {
                    self.enter(machine, to);
                }
            }
        }
    }
}

/// Whether the whole of `subject` is accepted by `machine`, with what `cache` has kept of earlier
/// matches against it.
///
/// Every state the machine may be in is walked at once, a scalar value at a time, and nothing is gone
/// back over, so a match takes time linear in the subject. Where the set the walk is in is kept, a
/// character whose class has been read from it before is one lookup, in [`run_known`]. A class not
/// read from it before is worked out by moving each state of the set ([`Walk::advance`]), at most
/// the machine's, and the set it comes to is looked for among the kept ones, or kept
/// ([`Cache::keep`]). Where sets are not kept, every character is worked out so ([`walk_on`]).
///
/// The loops whose count turns on the subject, the machine or the kept sets are these, and no other:
/// [`run_known`] and [`walk_on`] over the subject; [`Walk::advance`] and [`Walk::enter`] over the
/// steps and free steps of the states they move; [`Cache::keep`] over the slots it looks in and the
/// row it makes, [`Cache::same`] over a kept set, and [`Cache::grow`] over the kept sets; and
/// [`Walk::next_set`], over every state, once in four billion sets.
pub(crate) fn matches(machine: &Machine, cache: &mut Cache, subject: &str) -> bool {
    let classes = machine.classes.count();
    if cache.off {
        cache.walk.begin(machine);
        return walk_on(machine, &mut cache.walk, subject.chars());
    }
    let mut at = match cache.start {
        Some(start) => start,
        None => {
            cache.walk.begin(machine);
            match cache.keep(classes) {
                Some((start, _)) => {
                    cache.start = Some(start);
                    start
                }
                None => return walk_on(machine, &mut cache.walk, subject.chars()),
            }
        }
    };
    let mut i = 0;
    loop {
        let from = i;
        let stop = run_known(&cache.next, &machine.classes, subject, &mut i, &mut at);
        // What is read is counted in bytes, which is all the choice to keep sets asks of it.
        cache.read = cache.read.saturating_add(i - from);
        let Some((slot, c)) = stop else {
            return cache.accepting[at as usize / classes];
        };
        if cache.next[slot] == NONE {
            return false;
        }
        cache
            .walk
            .advance(machine, &cache.sets[at as usize / classes], c);
        i += c.len_utf8();
        match cache.keep(classes) {
            Some((next, forgot)) => {
                if !forgot {
                    cache.next[slot] = next;
                }
                if next == NONE {
                    return false;
                }
                at = next;
            }
            // The walk goes on a state at a time from the set it came to, in `next`.
            None => return walk_on(machine, &mut cache.walk, subject[i..].chars()),
        }
    }
}

/// Reads `subject` from byte `i` by the steps kept in `next`, from the kept set whose row begins at
/// `at`, moving both, until the end, where it answers `None`, or until a character whose step from
/// where it is is not one to a kept set, where it answers the slot of `next` that step is in and the
/// character, neither read. An ASCII character is its byte, and its class is looked up by it; any
/// other is decoded.
fn run_known(
    next: &[u32],
    classes: &Classes,
    subject: &str,
    i: &mut usize,
    at: &mut u32,
) -> Option<(usize, char)> {
    let bytes = subject.as_bytes();
    let ascii = classes.ascii();
    let (mut here, mut row) = (*i, *at);
    let stop = 'read: loop {
        // Four ASCII characters at a time where there are four, each step still waiting on the one
        // before it: written out, the steps cost about a quarter of what they cost one to a turn of
        // the loop.
        while let Some(&[a, b, c, d]) = bytes
            .get(here..here + 4)
            .and_then(|four| <&[u8; 4]>::try_from(four).ok())
            && u32::from_ne_bytes([a, b, c, d]) & 0x8080_8080 == 0
        {
            let step = |row: u32, byte: u8| {
                let slot = row as usize + ascii[usize::from(byte)];
                (slot, next[slot])
            };
            let (slot, one) = step(row, a);
            if one >= NONE {
                break 'read Some(slot);
            }
            let (slot, two) = step(one, b);
            if two >= NONE {
                (here, row) = (here + 1, one);
                break 'read Some(slot);
            }
            let (slot, three) = step(two, c);
            if three >= NONE {
                (here, row) = (here + 2, two);
                break 'read Some(slot);
            }
            let (slot, four) = step(three, d);
            if four >= NONE {
                (here, row) = (here + 3, three);
                break 'read Some(slot);
            }
            (here, row) = (here + 4, four);
        }
        let Some(&byte) = bytes.get(here) else {
            break None;
        };
        let (class, width) = if byte < 0x80 {
            (ascii[usize::from(byte)], 1)
        } else {
            let c = subject[here..]
                .chars()
                .next()
                .expect("a byte past ASCII begins a character here");
            (classes.class(c), c.len_utf8())
        };
        let slot = row as usize + class;
        let known = next[slot];
        // A kept set is the place its row begins, below both of the values that are not one.
        if known >= NONE {
            break Some(slot);
        }
        row = known;
        here += width;
    };
    (*i, *at) = (here, row);
    stop.map(|slot| {
        (
            slot,
            subject[here..]
                .chars()
                .next()
                .expect("a character begins here"),
        )
    })
}

/// The rest of a match a state at a time, from the set the walk has just come to, in `walk.next`.
fn walk_on(machine: &Machine, walk: &mut Walk, chars: core::str::Chars<'_>) -> bool {
    for c in chars {
        if walk.next.is_empty() {
            return false;
        }
        core::mem::swap(&mut walk.now, &mut walk.next);
        let from = core::mem::take(&mut walk.now);
        walk.advance(machine, &from, c);
        walk.now = from;
    }
    walk.accepting
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{PatternRead, read_pattern};
    use alloc::format;
    use alloc::string::String;

    /// A small generator of numbers, so that the texts below are the same on every run.
    struct Numbers(u64);

    impl Numbers {
        fn below(&mut self, n: usize) -> usize {
            self.0 = self
                .0
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            ((self.0 >> 33) % n as u64) as usize
        }
    }

    fn pattern(text: &str) -> crate::Pattern {
        match read_pattern(text) {
            PatternRead::Pattern(pattern) => pattern,
            other => panic!("{text}: {other:?}"),
        }
    }

    /// What a walk a state at a time answers, keeping no sets.
    fn state_at_a_time(pattern: &crate::Pattern, subject: &str) -> bool {
        let mut cache = Cache::new();
        cache.off = true;
        let super::super::Run::Steps(machine) = &pattern.run else {
            unreachable!("a pattern read from text is walked by its steps")
        };
        matches(machine, &mut cache, subject)
    }

    /// Patterns made at random of a few symbols and every shape, and subjects of the same symbols:
    /// a match that keeps sets, in one cache for every subject, answers what a walk a state at a time
    /// does.
    #[test]
    fn keeping_sets_changes_no_answer() {
        let mut numbers = Numbers(1);
        let atoms = ["a", "b", "é", "😀", ".", "[ab]", "[^a]", "\\d"];
        let shapes = [
            "{}{}",
            "{}|{}",
            "(?:{}){}",
            "{}*",
            "{}+",
            "{}?",
            "{}{2,3}",
            "(?:{}|{})*",
            "{}{1,}",
        ];
        let symbols = ['a', 'b', 'é', '😀', '1', 'c'];
        for _ in 0..2_000 {
            let mut text = String::from(atoms[numbers.below(atoms.len())]);
            for _ in 0..numbers.below(5) {
                let other = atoms[numbers.below(atoms.len())];
                let shape = shapes[numbers.below(shapes.len())];
                text = shape
                    .replacen("{}", &format!("(?:{text})"), 1)
                    .replacen("{}", other, 1);
            }
            let pattern = pattern(&text);
            let mut matcher = pattern.matcher();
            for _ in 0..20 {
                let subject: String = (0..numbers.below(12))
                    .map(|_| symbols[numbers.below(symbols.len())])
                    .collect();
                let expected = state_at_a_time(&pattern, &subject);
                assert_eq!(
                    matcher.matches(&subject),
                    expected,
                    "{text} against {subject:?}"
                );
                assert_eq!(
                    pattern.matches(&subject),
                    expected,
                    "{text} against {subject:?}"
                );
            }
        }
    }

    /// A pattern whose deterministic machine has more sets than a cache keeps: they are forgotten,
    /// and keeping them is given up on, and the answers are the same.
    #[test]
    fn a_cache_that_fills_forgets_and_gives_up_and_answers_the_same() {
        // The 17th character from the end is an a.
        let pattern = pattern("(?:a|b)*a(?:a|b){16}");
        let mut matcher = pattern.matcher();
        let mut numbers = Numbers(7);
        for _ in 0..6 {
            let subject: String = (0..20_000)
                .map(|_| if numbers.below(2) == 0 { 'a' } else { 'b' })
                .collect();
            let expected = subject.as_bytes()[subject.len() - 17] == b'a';
            assert_eq!(matcher.matches(&subject), expected);
        }
        assert!(matcher.cache.off, "the cache gave up keeping sets");
    }
}
