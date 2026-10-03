use alloc::vec;
use alloc::vec::Vec;

use super::machine::{Classes, Machine};
use super::subject::read_classes;

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
/// kept changes how fast a match is and no answer.
///
/// What is kept here is what was worked out and nothing about how a match is to walk. Whether a
/// match keeps the sets it comes to, starts them again or goes on without adding to them is decided
/// within the match ([`Keeping`]) and ends with it, so no match is walked otherwise for what an
/// earlier one read: the next starts as the first did.
///
/// A set is the same set in whatever order its states were come to, so it is found by a hash that
/// does not turn on the order, summed over its states where it is looked up ([`hash_of`]), and told
/// apart from another of the same hash by asking each of its states whether the walk has just
/// entered it ([`Cache::same`]). Whether a walk that ends in it accepts is asked of its states only
/// where it is kept ([`accepts`]). Nothing here puts a set in order, and nothing goes over a set but
/// those loops.
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
    walk: Walk,
}

/// What one match decides about keeping the sets it comes to, which ends with the match.
///
/// A match keeps each set it comes to that is not kept. Where there is no room for one, the kept
/// sets are forgotten and the match keeps sets again from there. The first time, it does so without
/// asking anything: what was kept was worked out by earlier matches, and how often they looked it up
/// says nothing about this one. After that, they are forgotten again only where what this match read
/// by kept steps since it last forgot them is ten times the sets it made; where it is less, keeping
/// them has saved it nothing, and the match is frozen for the rest of the subject.
///
/// A frozen match keeps no new set and no new step, so what is kept is no larger for it. It reads by
/// the steps already worked out from a kept set as any match does. A step not worked out is taken a
/// state at a time ([`Walk::advance`]), and the set it comes to is looked for among the kept ones: the
/// match goes back to reading by kept steps where it is one, and goes on a state at a time where it
/// is not ([`Cache::walk_alone`]). A match that fills the room with sets it does not come back to
/// pays for keeping two rooms of them, and is walked a state at a time after that; one whose sets are
/// looked up again pays for keeping them out of what it saves.
///
/// A set larger than the room is kept alone, once the others are forgotten, so a match that comes to
/// it again and again reads it by kept steps as it would a small one.
pub(crate) struct Keeping {
    /// Whether this match has forgotten the kept sets once.
    restarted: bool,
    /// Whether this match keeps no more sets.
    frozen: bool,
    /// The sets this match has made, and the bytes it has read by kept steps, since it last forgot
    /// the kept sets.
    made: usize,
    read: usize,
}

impl Keeping {
    fn new() -> Keeping {
        Keeping {
            restarted: false,
            frozen: false,
            made: 0,
            read: 0,
        }
    }
}

/// What came of looking for the set a walk has come to among the kept ones, and keeping it where it
/// is not: the place its row begins, where it was kept already or is kept now, or that there is no
/// room for it. What is done where there is none is the match's to decide ([`Cache::come_to`]).
enum Kept {
    Found(u32),
    Added(u32),
    Full,
}

/// How a walk a state at a time ended: with the subject read, and whether it is accepted; or at a
/// kept set, as the place its row begins, from which the walk goes on by kept steps.
enum Alone {
    Ended(bool),
    Rejoined(u32),
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
            walk: Walk {
                entered: Vec::new(),
                generation: 0,
                pending: Vec::new(),
                now: Vec::new(),
                next: Vec::new(),
            },
        }
    }

    /// Forgets every kept set. What is dropped is at most what [`KNOWN_BYTES`] holds, or one set
    /// larger than that.
    fn forget(&mut self) {
        self.sets.clear();
        self.hashes.clear();
        self.accepting.clear();
        self.slots.clear();
        self.next.clear();
        self.start = None;
        self.bytes = 0;
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins, as
    /// `keeping` decides: found, or kept, or `None` where the match keeps no more sets and it is not
    /// kept. The second answer is whether the sets kept before were forgotten to make room for it. The
    /// set is still in `walk.next` either way.
    fn come_to(
        &mut self,
        machine: &Machine,
        classes: usize,
        keeping: &mut Keeping,
    ) -> Option<(u32, bool)> {
        if keeping.frozen {
            return self.find(classes);
        }
        match self.keep(machine, classes) {
            Kept::Found(row) => Some((row, false)),
            Kept::Added(row) => {
                keeping.made += 1;
                Some((row, false))
            }
            Kept::Full => {
                if keeping.restarted && keeping.read < 10 * keeping.made {
                    keeping.frozen = true;
                    return None;
                }
                self.forget();
                keeping.restarted = true;
                keeping.made = 1;
                keeping.read = 0;
                Some((self.admit(machine, classes, hash_of(&self.walk.next)), true))
            }
        }
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins, or
    /// `None` where it is not kept.
    fn find(&self, classes: usize) -> Option<(u32, bool)> {
        if self.walk.next.is_empty() {
            return Some((NONE, false));
        }
        self.found(hash_of(&self.walk.next), classes)
            .map(|row| (row, false))
    }

    /// The kept set whose hash is `hash` and that is the set in `walk.next`, as the place its row
    /// begins, looked for from the slot the hash names to the first empty one.
    fn found(&self, hash: u32, classes: usize) -> Option<u32> {
        if self.slots.is_empty() {
            return None;
        }
        let mask = self.slots.len() - 1;
        let mut at = hash as usize & mask;
        while self.slots[at] != EMPTY {
            let kept = self.slots[at] as usize;
            if self.hashes[kept] == hash && Cache::same(&self.sets[kept], &self.walk) {
                return Some((kept * classes) as u32);
            }
            at = (at + 1) & mask;
        }
        None
    }

    /// The kept set the walk has just come to, in `walk.next`: found where it is kept, kept now where
    /// there is room for it, and otherwise [`Kept::Full`].
    fn keep(&mut self, machine: &Machine, classes: usize) -> Kept {
        if self.walk.next.is_empty() {
            return Kept::Found(NONE);
        }
        let hash = hash_of(&self.walk.next);
        if let Some(row) = self.found(hash, classes) {
            return Kept::Found(row);
        }
        if self.bytes + cost(classes, self.walk.next.len()) > KNOWN_BYTES {
            return Kept::Full;
        }
        Kept::Added(self.admit(machine, classes, hash))
    }

    /// Keeps the set in `walk.next`, which is not kept, whose hash is `hash`, as a copy of its
    /// states in a list of its own, and answers the place its row begins. It is kept whatever room
    /// is left: [`Cache::keep`] asks first, and a set kept after every other was forgotten is kept
    /// even where it is larger than the room, alone.
    ///
    /// The set is copied and not moved out of the walk: moved, it would leave `walk.next` with no
    /// room, to be grown again for each set after it, and it would keep the room `walk.next` had
    /// grown to, up to twice its states. The copy asks for room for its states, the four bytes a
    /// state that [`KNOWN_BYTES`] counts.
    fn admit(&mut self, machine: &Machine, classes: usize, hash: u32) -> u32 {
        if self.walk.next.is_empty() {
            return NONE;
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
        let accepting = accepts(machine, &self.walk.next);
        // A kept set is named by where its row of `next` begins, so a step is one lookup and no
        // product.
        let row = self.next.len() as u32;
        for _ in 0..classes {
            self.next.push(UNKNOWN);
        }
        let mut states = Vec::with_capacity(self.walk.next.len());
        for &q in &self.walk.next {
            states.push(q);
        }
        self.bytes += cost(classes, states.len());
        self.sets.push(states);
        self.hashes.push(hash);
        self.accepting.push(accepting);
        row
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

    /// Walks `subject` from byte `*i` a state at a time, from the set in `walk.next`, keeping
    /// nothing: the way a frozen match takes the steps not worked out. After each character the set
    /// the walk comes to is looked for among the kept ones, and where it is one the walk stops there,
    /// `*i` past that character, to go on by kept steps.
    fn walk_alone(
        &mut self,
        machine: &Machine,
        classes: usize,
        subject: &str,
        i: &mut usize,
    ) -> Alone {
        for c in subject[*i..].chars() {
            let walk = &mut self.walk;
            core::mem::swap(&mut walk.now, &mut walk.next);
            let from = core::mem::take(&mut walk.now);
            walk.advance(machine, &from, c);
            walk.now = from;
            *i += c.len_utf8();
            if walk.next.is_empty() {
                return Alone::Ended(false);
            }
            if let Some(row) = self.found(hash_of(&self.walk.next), classes) {
                return Alone::Rejoined(row);
            }
        }
        Alone::Ended(accepts(machine, &self.walk.next))
    }
}

/// What a kept set is counted as against [`KNOWN_BYTES`]: its row, its states, and what holds them.
fn cost(classes: usize, states: usize) -> usize {
    classes * 4 + states * 4 + 64
}

/// The sum of [`scatter`] over `states`, the same in whatever order they are in. It is summed where
/// a set is looked up and not as each state is entered.
fn hash_of(states: &[u32]) -> u32 {
    let mut hash = 0u32;
    for &q in states {
        hash = hash.wrapping_add(scatter(q));
    }
    hash
}

/// Whether a walk that ends in `states` accepts: whether any of them is one the machine may stop at.
/// It is asked where the answer is needed, where a set is kept and where a walk without kept sets
/// ends, and not as each state is entered.
fn accepts(machine: &Machine, states: &[u32]) -> bool {
    for &q in states {
        if machine.accepting[q as usize] {
            return true;
        }
    }
    false
}

/// A state's part of the hash of a set it is in, which is the same in whatever order the set's
/// states are put in.
fn scatter(q: u32) -> u32 {
    let mixed = q.wrapping_mul(0x9E37_79B9);
    mixed ^ (mixed >> 15)
}

/// The room a walk a state at a time works in: the generation each state was last entered in, the
/// states the free steps are still to be followed from, and the set the walk is in and the one it is
/// coming to.
///
/// Every walk enters states through [`Walk::enter`], with kept sets or without, so it does only what
/// both need. What only keeping a set needs, its hash and whether it accepts, is worked out of the
/// set where it is kept ([`hash_of`], [`accepts`]), and nothing of it is held here.
///
/// `pending`, `now` and `next` start with no room and grow as a walk holds more states in them, as
/// many as it has held at once and never more than the machine's states. Nothing takes that room away
/// from the walk: a kept set is a copy of the states in `next` ([`Cache::admit`]), so the next
/// set is put in the room the last one had, and a walk pays for room only as its sets need it.
struct Walk {
    entered: Vec<u32>,
    generation: u32,
    pending: Vec<u32>,
    now: Vec<u32>,
    next: Vec<u32>,
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
    }

    /// Puts `q` in `next`, with every state the free steps reach from it, each once.
    fn enter(&mut self, machine: &Machine, q: u32) {
        let add = |walk: &mut Walk, q: u32| {
            if walk.entered[q as usize] == walk.generation {
                return false;
            }
            walk.entered[q as usize] = walk.generation;
            walk.next.push(q);
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
/// the machine's, and the set it comes to is looked for among the kept ones, or kept, as the match
/// decides ([`Cache::come_to`], [`Keeping`]). A match that keeps no more sets takes the steps not
/// worked out a state at a time ([`Cache::walk_alone`]) until it comes to a kept set.
///
/// The loops whose count turns on the subject, the machine or the kept sets are these, and no other:
/// [`walk_with`] over the places the subject leads out of the kept steps, [`read_classes`], which
/// `run_known` reads through, and [`Cache::walk_alone`] over the subject; [`Walk::advance`] and
/// [`Walk::enter`] over the steps and free steps of the states they move; [`hash_of`] and
/// [`accepts`] over a set; [`Cache::found`] over the slots it looks in, [`Cache::admit`] over the
/// slots it looks in, the set it copies and the row it makes, [`Cache::same`] over a kept set, and
/// [`Cache::grow`] over the kept sets; and [`Walk::next_set`], over every state, once in four
/// billion sets. Making room is not a loop here: `vec!` makes `entered` once, and a list grows in
/// `push` by doubling, as far as the most states the walk has held in it at once, as `Walk` says.
/// The test `the_walk_names_every_loop_it_has` holds this list to the functions with a loop in them.
pub(crate) fn matches(machine: &Machine, cache: &mut Cache, subject: &str) -> bool {
    walk_with(machine, cache, subject, &mut Keeping::new())
}

/// [`matches`], with what the match decides about keeping sets held in `keeping`.
fn walk_with(machine: &Machine, cache: &mut Cache, subject: &str, keeping: &mut Keeping) -> bool {
    let classes = machine.classes.count();
    let mut i = 0;
    let mut at = match cache.start {
        Some(start) => start,
        None => {
            cache.walk.begin(machine);
            match cache.come_to(machine, classes, keeping) {
                Some((start, _)) => {
                    cache.start = Some(start);
                    start
                }
                None => match cache.walk_alone(machine, classes, subject, &mut i) {
                    Alone::Ended(answer) => return answer,
                    Alone::Rejoined(row) => row,
                },
            }
        }
    };
    loop {
        if at == NONE {
            return false;
        }
        let from = i;
        let stop = run_known(&cache.next, &machine.classes, subject, &mut i, &mut at);
        // What is read is counted in bytes, which is all the choice to keep sets asks of it.
        keeping.read += i - from;
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
        at = match cache.come_to(machine, classes, keeping) {
            Some((next, forgot)) => {
                // A frozen match writes no step, and one that forgot has no row to write it in.
                if !forgot && !keeping.frozen {
                    cache.next[slot] = next;
                }
                next
            }
            // The walk goes on a state at a time from the set it came to, in `next`.
            None => match cache.walk_alone(machine, classes, subject, &mut i) {
                Alone::Ended(answer) => return answer,
                Alone::Rejoined(row) => row,
            },
        };
    }
}

/// Reads `subject` from byte `i` by the steps kept in `next`, from the kept set whose row begins at
/// `at`, moving both, until the end, where it answers `None`, or until a character whose step from
/// where it is is not one to a kept set, where it answers the slot of `next` that step is in and the
/// character, neither read. The subject is read as every walk reads one ([`read_classes`]).
fn run_known(
    next: &[u32],
    classes: &Classes,
    subject: &str,
    i: &mut usize,
    at: &mut u32,
) -> Option<(usize, char)> {
    let mut row = *at;
    // A kept set is the place its row begins, below both of the values that are not one.
    let stopped = read_classes(
        subject,
        i,
        classes.ascii(),
        |c| classes.class_of(c),
        &mut row,
        |row, class| next[row as usize + class],
        |known| known >= NONE,
    );
    *at = row;
    stopped.map(|class| {
        (
            row as usize + class,
            subject[*i..]
                .chars()
                .next()
                .expect("a character begins here"),
        )
    })
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

        /// `n` characters, each a or b at random.
        fn ab(&mut self, n: usize) -> String {
            (0..n)
                .map(|_| if self.below(2) == 0 { 'a' } else { 'b' })
                .collect()
        }
    }

    fn pattern(text: &str) -> crate::Pattern {
        match read_pattern(text) {
            PatternRead::Pattern(pattern) => pattern,
            other => panic!("{text}: {other:?}"),
        }
    }

    fn machine(pattern: &crate::Pattern) -> &Machine {
        let super::super::Run::Steps(machine) = &pattern.run else {
            unreachable!("a pattern read from text is walked by its steps")
        };
        machine
    }

    /// What a walk a state at a time answers, keeping no sets.
    fn state_at_a_time(pattern: &crate::Pattern, subject: &str) -> bool {
        let machine = machine(pattern);
        let mut cache = Cache::new();
        cache.walk.begin(machine);
        let mut i = 0;
        match cache.walk_alone(machine, machine.classes.count(), subject, &mut i) {
            Alone::Ended(answer) => answer,
            Alone::Rejoined(_) => unreachable!("nothing is kept"),
        }
    }

    /// The match `matcher` makes of `subject`, and what it decided about keeping sets.
    fn decided(matcher: &mut crate::Matcher<'_>, subject: &str) -> (bool, Keeping) {
        let mut keeping = Keeping::new();
        let answer = walk_with(
            machine(matcher.pattern),
            &mut matcher.cache,
            subject,
            &mut keeping,
        );
        (answer, keeping)
    }

    /// Whether the 17th character from the end of `subject` is an a, which `(?:a|b)*a(?:a|b){16}`
    /// asks.
    fn seventeenth(subject: &str) -> bool {
        subject.len() >= 17 && subject.as_bytes()[subject.len() - 17] == b'a'
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

    /// The paths a match goes by, each timed apart by [`walk_paths`] and held here to the way it is
    /// named, so that what a time says is of that way. Work put in a step every path takes for the
    /// sake of one is paid by the others, so a change is compared on each: one time over all would
    /// let what one loses be hidden by what another gains.
    mod paths {
        use super::*;

        /// The sets of `(?:a?){49998}` are as large as the machine and a new one at each character,
        /// so a match fills the room twice and is frozen, and walks the rest a state at a time.
        pub(super) fn large() -> (crate::Pattern, String) {
            (pattern("(?:a?){49998}"), "a".repeat(100))
        }

        /// The tenth character from the end is an a, and the subject is at random: a match that has
        /// kept nothing comes to a new set at most characters.
        pub(super) fn tenth() -> (crate::Pattern, String) {
            let mut seed = 9u32;
            let subject = (0..400)
                .map(|_| {
                    seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                    if seed >> 31 == 0 { 'a' } else { 'b' }
                })
                .collect();
            (pattern("(?:a|b)*a(?:a|b){8}"), subject)
        }

        /// Forgets where each class leads from each kept set, and keeps the sets: the next match
        /// works out each step again, and finds the set it comes to among those kept by its hash.
        pub(super) fn forget_steps(cache: &mut Cache) {
            cache.next.fill(UNKNOWN);
        }
    }

    /// Each path [`walk_paths`] times goes the way it is named: a match over sets larger than the
    /// room is frozen; one with nothing kept keeps a new set at most characters; one over steps
    /// already worked out reads the whole subject by them; and one whose steps are forgotten finds
    /// the set at most characters lead to among those kept, keeping no new one.
    #[test]
    fn each_timed_path_goes_the_way_it_is_named() {
        let (large, a) = paths::large();
        let mut alone = large.matcher();
        let (_, keeping) = decided(&mut alone, &a);
        assert!(keeping.restarted && keeping.frozen, "the match was frozen");

        let (tenth, subject) = paths::tenth();
        let mut fresh = tenth.matcher();
        let (_, keeping) = decided(&mut fresh, &subject);
        assert!(
            keeping.made * 2 > subject.len() && !keeping.frozen,
            "a new set at {} of {} characters",
            keeping.made,
            subject.len()
        );

        let (_, keeping) = decided(&mut fresh, &subject);
        assert_eq!(keeping.made, 0, "no set made over steps worked out");
        assert_eq!(keeping.read, subject.len(), "every character read by them");

        paths::forget_steps(&mut fresh.cache);
        let (_, keeping) = decided(&mut fresh, &subject);
        let found = subject.len() - keeping.read;
        assert_eq!(keeping.made, 0, "no set made where each is kept");
        assert!(
            found * 2 > subject.len() && !keeping.frozen,
            "a kept set found at {found} of {} characters",
            subject.len()
        );
    }

    /// How long a match takes on each path, which [`each_timed_path_goes_the_way_it_is_named`]
    /// holds to its name. Not run with the others:
    ///
    /// ```sh
    /// cargo test --release --lib walk_paths -- --ignored --nocapture
    /// ```
    #[test]
    #[ignore = "a timing, run by hand"]
    fn walk_paths() {
        extern crate std;
        use std::time::{Duration, Instant};
        /// The mean time of `f` over about a second of calls, each after `before`, which is out of
        /// the time.
        fn time(mut before: impl FnMut(), mut f: impl FnMut()) -> Duration {
            let (start, mut times, mut timed) = (Instant::now(), 0u32, Duration::ZERO);
            while start.elapsed() < Duration::from_secs(1) || times < 3 {
                before();
                let each = Instant::now();
                f();
                timed += each.elapsed();
                times += 1;
            }
            timed / times
        }

        let (large, a) = paths::large();
        let mut alone = large.matcher();
        let without = time(
            || {},
            || {
                core::hint::black_box(alone.matches(&a));
            },
        );

        let (tenth, subject) = paths::tenth();
        let new = time(
            || {},
            || {
                core::hint::black_box(tenth.matches(&subject));
            },
        );

        let mut kept = tenth.matcher();
        kept.matches(&subject);
        let steps = time(
            || {},
            || {
                core::hint::black_box(kept.matches(&subject));
            },
        );
        // The steps are forgotten before each match, out of its time; the cache is moved between
        // the two by a cell, since both hold it.
        let kept = core::cell::RefCell::new(kept);
        let found = time(
            || paths::forget_steps(&mut kept.borrow_mut().cache),
            || {
                core::hint::black_box(kept.borrow_mut().matches(&subject));
            },
        );

        std::println!(
            "{:<56} {without:>10.2?}",
            "frozen, (?:a?){49998} against 100 a"
        );
        std::println!(
            "{:<56} {new:>10.2?}",
            "a new set kept at most characters, 400 bytes"
        );
        std::println!(
            "{:<56} {steps:>10.2?}",
            "steps already worked out, 400 bytes"
        );
        std::println!(
            "{:<56} {found:>10.2?}",
            "kept sets found by their hash, 400 bytes"
        );
    }

    /// The list of loops in [`matches`]'s doc is held to the code: each function of this file with a
    /// loop in it is named, each name is a function with a loop, here or in `subject.rs`, and the
    /// code calls nothing that goes over a list out of sight. The list was written by hand and once
    /// named a function with no loop and left out one with a loop, as Go's did before its test.
    #[test]
    fn the_walk_names_every_loop_it_has() {
        use alloc::collections::BTreeSet;
        use alloc::string::ToString;
        use alloc::vec::Vec;

        /// The code of `source` before its tests, each line without what follows `//`.
        fn code(source: &str) -> Vec<&str> {
            let before = source.find("#[cfg(test)]").unwrap_or(source.len());
            source[..before]
                .lines()
                .map(|line| line.find("//").map_or(line, |at| &line[..at]))
                .collect()
        }

        /// Each function of `lines` with a loop in it, named as the doc names it.
        fn looping(lines: &[&str]) -> BTreeSet<String> {
            let mut out = BTreeSet::new();
            let mut within = None;
            let mut function: Option<String> = None;
            for line in lines {
                if let Some(rest) = line.strip_prefix("impl ") {
                    within = rest.split_whitespace().next().map(|name| name.to_string());
                } else if line.starts_with('}') {
                    within = None;
                }
                let trimmed = line.trim_start();
                let named = trimmed
                    .strip_prefix("pub(crate) fn ")
                    .or_else(|| trimmed.strip_prefix("fn "));
                if let Some(rest) = named {
                    let name = &rest[..rest.find(['(', '<']).expect("a function's name ends")];
                    let top = !line.starts_with(' ');
                    function = Some(match (&within, top) {
                        (Some(owner), false) => format!("{owner}::{name}"),
                        _ => name.to_string(),
                    });
                    if top {
                        within = None;
                    }
                }
                let loops = trimmed.starts_with("for ")
                    || trimmed.starts_with("while ")
                    || trimmed.contains("loop {");
                if loops && let Some(name) = &function {
                    out.insert(name.clone());
                }
            }
            out
        }

        let walk = code(include_str!("walk.rs"));
        let subject = code(include_str!("subject.rs"));
        let doc: String = include_str!("walk.rs")
            .lines()
            .skip_while(|line| !line.starts_with("/// The loops whose count"))
            .take_while(|line| line.starts_with("///"))
            .collect();
        let named: BTreeSet<String> = doc
            .split("[`")
            .skip(1)
            .map(|link| link[..link.find("`]").expect("a link ends")].to_string())
            .collect();
        let here = looping(&walk);
        let elsewhere = looping(&subject);
        for name in &here {
            assert!(
                named.contains(name),
                "{name} has a loop the doc does not name"
            );
        }
        for name in &named {
            assert!(
                here.contains(name) || elsewhere.contains(name),
                "{name} is named and has no loop"
            );
        }
        // What goes over a list in one call, out of sight of the loops above.
        let hidden = [
            ".extend(",
            ".clone()",
            ".to_vec()",
            ".collect",
            ".sort",
            ".fill(",
            ".resize(",
            ".contains(",
            ".retain(",
            ".drain(",
            "copy_from_slice(",
            ".iter()",
            ".into_iter()",
            ".concat(",
            ".join(",
            ".repeat(",
            ".position(",
            ".any(",
            ".all(",
            ".sum(",
        ];
        for (at, line) in walk.iter().enumerate() {
            for call in hidden {
                assert!(!line.contains(call), "line {}: {call}", at + 1);
            }
            if line.starts_with("use ") {
                assert!(
                    [
                        "use alloc::vec;",
                        "use alloc::vec::Vec;",
                        "use super::machine::{Classes, Machine};",
                        "use super::subject::read_classes;",
                    ]
                    .contains(line),
                    "line {}: {line}",
                    at + 1
                );
            }
        }
    }

    /// Keeping a set takes no room from the walk: `next` has the room it had before, and still holds
    /// the set. Moving `next` out of the walk instead would leave it no room, to be grown again for
    /// every set after. How much room the kept copy has is the allocator's to say, at least its
    /// states, and is not held here.
    #[test]
    fn keeping_a_set_takes_no_room_from_the_walk() {
        let pattern = pattern("(?:a|b)*a(?:a|b){8}");
        let machine = machine(&pattern);
        let classes = machine.classes.count();
        let mut matcher = pattern.matcher();
        let subject = Numbers(7).ab(400);
        matcher.matches(&subject);
        let cache = &mut matcher.cache;
        let sets = cache.sets.clone();
        assert!(sets.len() > 10, "{} sets kept", sets.len());
        for (at, set) in sets.into_iter().enumerate() {
            cache.forget();
            cache.walk.next_set(machine);
            for &q in &set {
                cache.walk.enter(machine, q);
            }
            let room = cache.walk.next.capacity();
            let kept = cache.keep(machine, classes);
            assert!(matches!(kept, Kept::Added(0)), "set {at} is kept anew");
            assert_eq!(
                cache.walk.next.capacity(),
                room,
                "set {at} took the walk's room"
            );
            assert_eq!(cache.walk.next, set, "set {at} is still the walk's");
            assert_eq!(cache.sets[0], set, "set {at} is kept as it is");
        }
    }

    /// A pattern whose deterministic machine has more sets than a cache keeps, against subjects at
    /// random: each match forgets the kept sets once without asking, is frozen once it fills the room
    /// again with sets it does not come back to, and answers what a walk a state at a time does.
    #[test]
    fn a_match_that_fills_the_room_twice_with_new_sets_is_frozen_and_answers_the_same() {
        let pattern = pattern("(?:a|b)*a(?:a|b){16}");
        let mut matcher = pattern.matcher();
        let mut numbers = Numbers(7);
        for _ in 0..3 {
            let subject = numbers.ab(20_000);
            let (answer, keeping) = decided(&mut matcher, &subject);
            assert_eq!(answer, seventeenth(&subject));
            assert!(keeping.restarted && keeping.frozen, "{}", keeping.made);
        }
    }

    /// What the cache holds says nothing of how a match walks: after a match that was frozen, the
    /// next starts keeping sets as the first match of a new matcher does, forgets what the frozen one
    /// left without asking, and the one after it reads its whole subject by kept steps. Before, a
    /// matcher that gave up walked every match a state at a time until its matches had walked 32
    /// megabytes, and twice as many after each try that gave up again.
    #[test]
    fn a_frozen_match_leaves_nothing_that_slows_the_next() {
        let pattern = pattern("(?:a|b)*a(?:a|b){16}");
        let mut matcher = pattern.matcher();
        let (_, keeping) = decided(&mut matcher, &Numbers(7).ab(200_000));
        assert!(keeping.frozen);
        let friendly = "ab".repeat(400);
        let (answer, keeping) = decided(&mut matcher, &friendly);
        assert_eq!(answer, seventeenth(&friendly));
        assert!(!keeping.frozen, "the next match kept sets");
        // The set the walk starts in may have been forgotten as the match before went, and is kept
        // by the second.
        for _ in 0..2 {
            assert_eq!(decided(&mut matcher, &friendly).0, seventeenth(&friendly));
        }
        let (answer, keeping) = decided(&mut matcher, &friendly);
        assert_eq!(answer, seventeenth(&friendly));
        assert_eq!(keeping.made, 0, "no set made once they are kept");
        assert_eq!(
            keeping.read,
            friendly.len(),
            "every character read by kept steps"
        );
    }

    /// A frozen match keeps no set and no step, and goes back to reading by kept steps where a step
    /// taken a state at a time comes to a kept set: after an a, sixteen bs lead back to the set the
    /// walk starts in, which is kept, and from there every b is read by its kept step.
    #[test]
    fn a_frozen_match_adds_nothing_and_goes_back_to_kept_sets() {
        let pattern = pattern("(?:a|b)*a(?:a|b){16}");
        let mut matcher = pattern.matcher();
        assert!(!matcher.matches(&"b".repeat(20)));
        let sets = matcher.cache.sets.len();
        let next = matcher.cache.next.clone();
        let subject = format!("a{}", "b".repeat(100));
        let mut keeping = Keeping::new();
        keeping.frozen = true;
        let answer = walk_with(
            machine(&pattern),
            &mut matcher.cache,
            &subject,
            &mut keeping,
        );
        assert_eq!(answer, seventeenth(&subject));
        assert_eq!(matcher.cache.sets.len(), sets, "no set kept");
        assert_eq!(matcher.cache.next, next, "no step kept");
        assert_eq!(
            keeping.read,
            subject.len() - 18,
            "every b after the walk came back read by its kept step"
        );
    }

    /// A match that forgets the kept sets and finds them looked up again forgets them again rather
    /// than freezing: subjects whose sets come back keep being read by kept steps.
    #[test]
    fn a_match_whose_sets_are_looked_up_again_is_not_frozen() {
        let pattern = pattern("(?:a|b)*a(?:a|b){16}");
        let mut matcher = pattern.matcher();
        let mut numbers = Numbers(11);
        // Two thousand different stretches of a and b, each read thirty times over.
        let mut subject = String::new();
        for _ in 0..2_000 {
            let piece = numbers.ab(20);
            for _ in 0..30 {
                subject.push_str(&piece);
            }
        }
        let (answer, keeping) = decided(&mut matcher, &subject);
        assert_eq!(answer, seventeenth(&subject));
        assert!(keeping.restarted, "the room filled");
        assert!(
            !keeping.frozen,
            "made {}, read {}",
            keeping.made, keeping.read
        );
    }
}
