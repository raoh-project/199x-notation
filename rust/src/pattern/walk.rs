use alloc::vec;
use alloc::vec::Vec;

use super::machine::{Classes, Machine};
use super::subject::read_classes;

/// The most one cache asks the allocator to hold of the sets it has worked out and the steps between
/// them, counted by the room each list holds and not by how much of it is in use, as [`Cache`] says.
const KNOWN_BYTES: usize = 2 << 20;

/// Where a class leads from a kept set before that has been worked out.
const UNKNOWN: u32 = u32::MAX;
/// The kept set no state is in, from which no string is accepted.
const NONE: u32 = u32::MAX - 1;
/// A slot of [`Cache::slots`] no kept set is in.
const EMPTY: u32 = u32::MAX;
/// A slot of [`Cold`] no step is in. No row begins at `u32::MAX`, so no step's key is this.
const NO_STEP: u64 = u64::MAX;

/// What matches against one machine keep between them: the sets of states a walk has been in, and
/// where each class of characters leads from them. These are the sets a deterministic machine would
/// have, made only as a walk comes to them; which sets are kept changes how fast a match is and no
/// answer.
///
/// What is kept here is what was worked out and nothing about how a match is to walk. Whether a
/// match keeps the sets it comes to, starts them again or goes on without adding to them is decided
/// within the match ([`Keeping`]) and ends with it, so no match is walked otherwise for what an
/// earlier one read: the next starts as the first did.
///
/// Three promises hold what is kept, and each is kept in one place.
///
/// What is kept is held to [`KNOWN_BYTES`], but for what every match needs, counted as the room the
/// lists hold ([`Cache::room`]): the room a list has, not what of it is in use, so a list grown by doubling, or a table left a
/// quarter full by its growing, is counted as large as it is. A list is grown only to the room
/// [`room_for`] works out, so what is counted before anything is kept is what is held after; how far
/// the allocator rounds a request up is its own, and is not counted. The room the walk works in
/// ([`Walk`]) is not kept, and is not counted here: it is never more than four lists as long as the
/// machine's states.
///
/// Two sets are kept whatever they cost: the one a walk starts in, which every match needs, and the
/// one the walk has come to when the others are forgotten ([`Cache::hold`]), with the step between
/// them. [`Cache::forget`] keeps the first, so no match forgets the others to keep it, and a set
/// larger than the room is kept beside it. What they hold is not counted against [`KNOWN_BYTES`]
/// but apart from it ([`Cache::needed`]): the room bounds everything else, the other sets, the
/// steps and the lists that hold them. So a step from a set larger than the room is kept in the
/// room as any is, and what is kept is at most the room and those two sets, at most twice the
/// machine's states. Anything else, a set or a step, is kept where there is room for it, and where there is
/// not that is said to the match ([`Kept::Full`], [`Cache::learn`]), which decides
/// ([`Cache::make_room`]): nothing is refused without the match knowing, so no match goes on working
/// out one step again and again because there was no room to keep it.
///
/// The kept sets are forgotten in one place, [`Cache::make_room`], where the match decides to and
/// counts it ([`Keeping`]), so how many times one match starts the kept sets again is what its
/// decision says and nothing else.
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
    /// Where class `k` leads from the kept set whose row begins at `s` is `next[s + k]`, for each of
    /// the classes an ASCII character is in: the place the row of the set it leads to begins,
    /// [`NONE`], or [`UNKNOWN`]. The set whose row begins at `s` is `sets[s / width]`, `width` being
    /// how many those classes are ([`walk_with`]). Where the other classes lead is in `cold`.
    ///
    /// A row is as wide as the classes ASCII is in, at most 128, and not as wide as every class of
    /// the machine, which no limit on a pattern bounds: a set of 100,000 runs cuts the scalar values
    /// into 200,001 classes, and a row of each would make what a set costs that many times four
    /// bytes, for the classes past ASCII that most sets are never left by.
    next: Vec<u32>,
    cold: Cold,
    /// The kept set a walk starts in, where it is kept.
    start: Option<u32>,
    /// The room the states of every kept set hold, which [`Cache::held`] adds to the room of the
    /// lists here: each set's states are a list of their own, so what they hold is counted as each
    /// is kept, and they are not gone over to count it.
    state_room: usize,
    /// What keeping the sets every match needs, and the step between them, added to
    /// [`Cache::held`], which is held beside [`KNOWN_BYTES`] and not in it ([`Cache::room`]).
    needed: usize,
    walk: Walk,
}

/// What one match decides about keeping the sets it comes to and the steps between them, which ends
/// with the match.
///
/// A match keeps each set it comes to that is not kept, and each step it works out. Where there is no
/// room for one, the kept sets are forgotten and the match keeps sets again from there
/// ([`Cache::make_room`]). The first time, it does so without asking anything: what was kept was
/// worked out by earlier matches, and how often they looked it up says nothing about this one. After
/// that, they are forgotten again only where what this match read by kept steps since it last forgot
/// them is ten times the sets it made; where it is less, keeping them has saved it nothing, and the
/// match is frozen for the rest of the subject. So one match starts the kept sets again once for
/// nothing, and after that only as often as what it reads by them pays for what it keeps.
///
/// A frozen match keeps no new set and no new step, so what is kept is no larger for it. It reads by
/// the steps already worked out from a kept set as any match does. A step not worked out is taken a
/// state at a time ([`Walk::advance`]), and the set it comes to is looked for among the kept ones: the
/// match goes back to reading by kept steps where it is one, and goes on a state at a time where it
/// is not ([`Cache::walk_alone`]).
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
/// room for it. What is done where there is none is the match's to decide ([`Cache::make_room`]).
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
            cold: Cold::new(),
            start: None,
            state_room: 0,
            needed: 0,
            walk: Walk {
                entered: Vec::new(),
                generation: 0,
                pending: Vec::new(),
                now: Vec::new(),
                next: Vec::new(),
            },
        }
    }

    /// The room what is kept holds, as [`KNOWN_BYTES`] counts it: the room of each list, and of the
    /// states of each kept set.
    fn held(&self) -> usize {
        self.state_room
            + self.sets.capacity() * size_of::<Vec<u32>>()
            + self.hashes.capacity() * size_of::<u32>()
            + self.accepting.capacity() * size_of::<bool>()
            + self.slots.capacity() * size_of::<u32>()
            + self.next.capacity() * size_of::<u32>()
            + self.cold.held()
    }

    /// The room what is kept holds in [`KNOWN_BYTES`]: [`Cache::held`], but for what the sets every
    /// match needs, and the step between them, hold beside it.
    fn room(&self) -> usize {
        self.held() - self.needed
    }

    /// The room keeping one more set of `states` states would add to [`Cache::held`]: its states,
    /// and what each list it is put in grows by where it is full.
    fn room_for(&self, width: usize, states: usize) -> usize {
        let slots = if (self.sets.len() + 1) * 2 > self.slots.len() {
            (self.slots.len() * 2).max(16) - self.slots.capacity()
        } else {
            0
        };
        states * size_of::<u32>()
            + room_for(&self.sets, 1) * size_of::<Vec<u32>>()
            + room_for(&self.hashes, 1) * size_of::<u32>()
            + room_for(&self.accepting, 1) * size_of::<bool>()
            + room_for(&self.next, width) * size_of::<u32>()
            + slots * size_of::<u32>()
    }

    /// Forgets every kept set but the one a walk starts in, and where each class leads from that
    /// one, and lets go of the room they held. Every match needs that set, whatever it reads, so a
    /// match never forgets the others to make room for it, and the set a match forgot them for is
    /// kept beside it ([`Cache::hold`]). Called by [`Cache::make_room`] alone.
    fn forget(&mut self, width: usize) {
        let start = match self.start {
            Some(row) if row != NONE => Some((
                core::mem::take(&mut self.sets[row as usize / width]),
                self.hashes[row as usize / width],
                self.accepting[row as usize / width],
            )),
            _ => None,
        };
        self.sets = Vec::new();
        self.hashes = Vec::new();
        self.accepting = Vec::new();
        self.slots = Vec::new();
        self.next = Vec::new();
        self.cold = Cold::new();
        self.state_room = 0;
        self.needed = 0;
        if let Some((states, hash, accepting)) = start {
            self.start = Some(self.put(width, states, hash, accepting, true));
        }
    }

    /// What a match does where there is no room for a set or a step it would keep, as [`Keeping`]
    /// says: forgets the kept sets, but the one a walk starts in, and answers true; or is frozen,
    /// and answers false. The one place the kept sets are forgotten, so every time they are is
    /// counted by the match that did it.
    fn make_room(&mut self, width: usize, keeping: &mut Keeping) -> bool {
        if keeping.restarted && keeping.read < 10 * keeping.made {
            keeping.frozen = true;
            return false;
        }
        self.forget(width);
        keeping.restarted = true;
        keeping.made = 0;
        keeping.read = 0;
        true
    }

    /// Where class `class` leads from the kept set whose row begins at `row`: the place the row of
    /// the set it leads to begins, [`NONE`], or [`UNKNOWN`].
    fn step(&self, width: usize, row: u32, class: usize) -> u32 {
        if class < width {
            self.next[row as usize + class]
        } else {
            self.cold.get(row, class)
        }
    }

    /// Keeps that class `class` leads from the kept set whose row begins at `row` to `to`, which
    /// was not known, and answers whether it is kept. A class in the row is kept in it, whose room
    /// was counted when the set was kept. A class past the row is kept where there is room for it;
    /// where there is not, nothing is kept and the match is told, to decide what to do. With
    /// `needed`, it is kept whatever it costs, beside the room: asked only for the step from the set
    /// a walk starts in to the set it is in when the others were just forgotten for it.
    fn learn(&mut self, width: usize, row: u32, class: usize, to: u32, needed: bool) -> bool {
        if class < width {
            self.next[row as usize + class] = to;
            return true;
        }
        let more = self.cold.room_for_one();
        if needed {
            self.needed += more;
        } else if self.room() + more > KNOWN_BYTES {
            return false;
        }
        self.cold.insert(row, class, to);
        true
    }

    /// Keeps that class `class` leads from the set a walk starts in to `to`, the set the walk is in
    /// once the others were just forgotten for it, whatever it costs, beside the room.
    fn learn_from_start(&mut self, width: usize, class: usize, to: u32) {
        if let Some(start) = self.start
            && start != NONE
        {
            self.learn(width, start, class, to, true);
        }
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins, as
    /// `keeping` decides: found, or kept, or `None` where the match keeps no more sets and it is not
    /// kept. The second answer is whether the sets kept before were forgotten to make room for it. The
    /// set is still in `walk.next` either way.
    fn come_to(
        &mut self,
        machine: &Machine,
        width: usize,
        keeping: &mut Keeping,
    ) -> Option<(u32, bool)> {
        if keeping.frozen {
            return self.find(width);
        }
        match self.keep(machine, width) {
            Kept::Found(row) => Some((row, false)),
            Kept::Added(row) => {
                keeping.made += 1;
                Some((row, false))
            }
            Kept::Full => {
                if !self.make_room(width, keeping) {
                    return None;
                }
                Some((self.hold(machine, width, keeping), true))
            }
        }
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins: found
    /// where it is kept, and otherwise kept now, whatever it costs, and counted as made by the match.
    /// What every match needs is kept so: the set a walk starts in, and the set a walk is in once the
    /// others are forgotten.
    fn hold(&mut self, machine: &Machine, width: usize, keeping: &mut Keeping) -> u32 {
        if self.walk.next.is_empty() {
            return NONE;
        }
        let hash = hash_of(&self.walk.next);
        if let Some(row) = self.found(hash, width) {
            return row;
        }
        keeping.made += 1;
        self.admit(machine, width, hash, true)
    }

    /// The kept set the walk has just come to, in `walk.next`, as the place its row begins, or
    /// `None` where it is not kept.
    fn find(&self, width: usize) -> Option<(u32, bool)> {
        if self.walk.next.is_empty() {
            return Some((NONE, false));
        }
        self.found(hash_of(&self.walk.next), width)
            .map(|row| (row, false))
    }

    /// The kept set whose hash is `hash` and that is the set in `walk.next`, as the place its row
    /// begins, looked for from the slot the hash names to the first empty one.
    fn found(&self, hash: u32, width: usize) -> Option<u32> {
        if self.slots.is_empty() {
            return None;
        }
        let mask = self.slots.len() - 1;
        let mut at = hash as usize & mask;
        while self.slots[at] != EMPTY {
            let kept = self.slots[at] as usize;
            if self.hashes[kept] == hash && Cache::same(&self.sets[kept], &self.walk) {
                return Some((kept * width) as u32);
            }
            at = (at + 1) & mask;
        }
        None
    }

    /// The kept set the walk has just come to, in `walk.next`: found where it is kept, kept now where
    /// there is room for it, and otherwise [`Kept::Full`].
    fn keep(&mut self, machine: &Machine, width: usize) -> Kept {
        if self.walk.next.is_empty() {
            return Kept::Found(NONE);
        }
        let hash = hash_of(&self.walk.next);
        if let Some(row) = self.found(hash, width) {
            return Kept::Found(row);
        }
        if self.room() + self.room_for(width, self.walk.next.len()) > KNOWN_BYTES {
            return Kept::Full;
        }
        Kept::Added(self.admit(machine, width, hash, false))
    }

    /// Keeps the set in `walk.next`, which is not kept, whose hash is `hash`, as a copy of its
    /// states in a list of its own, and answers the place its row begins. It is kept whatever room
    /// is left: [`Cache::keep`] asks first, and [`Cache::hold`] keeps what every match needs, with
    /// `needed`, beside the room.
    ///
    /// The set is copied and not moved out of the walk: moved, it would leave `walk.next` with no
    /// room, to be grown again for each set after it, and it would keep the room `walk.next` had
    /// grown to, up to twice its states. The copy asks for room for its states.
    fn admit(&mut self, machine: &Machine, width: usize, hash: u32, needed: bool) -> u32 {
        if self.walk.next.is_empty() {
            return NONE;
        }
        let accepting = accepts(machine, &self.walk.next);
        let mut states = Vec::with_capacity(self.walk.next.len());
        for &q in &self.walk.next {
            states.push(q);
        }
        self.put(width, states, hash, accepting, needed)
    }

    /// Keeps `states`, a set not kept whose hash is `hash`, and answers the place its row begins,
    /// every class of the row not known yet. Each list is grown, where it is full, by what
    /// [`room_for`] says, so what [`Cache::room_for`] works out before is what is held after. With
    /// `needed`, what keeping it adds is held beside the room.
    fn put(
        &mut self,
        width: usize,
        states: Vec<u32>,
        hash: u32,
        accepting: bool,
        needed: bool,
    ) -> u32 {
        let before = self.held();
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
        make_room_for(&mut self.next, width);
        for _ in 0..width {
            self.next.push(UNKNOWN);
        }
        self.state_room += states.capacity() * size_of::<u32>();
        make_room_for(&mut self.sets, 1);
        self.sets.push(states);
        make_room_for(&mut self.hashes, 1);
        self.hashes.push(hash);
        make_room_for(&mut self.accepting, 1);
        self.accepting.push(accepting);
        if needed {
            self.needed += self.held() - before;
        }
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

    /// Twice the slots, or sixteen, in a list of that room, each kept set put in again by its hash.
    /// What is gone over is the kept sets.
    fn grow(&mut self) {
        let size = (self.slots.len() * 2).max(16);
        self.slots = Vec::with_capacity(size);
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
        width: usize,
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
            if let Some(row) = self.found(hash_of(&self.walk.next), width) {
                return Alone::Rejoined(row);
            }
        }
        Alone::Ended(accepts(machine, &self.walk.next))
    }
}

/// How many more items `list` would have room for were `more` pushed onto it: none where it has room
/// for them, and otherwise twice its room, or what it needs, or four, whichever is most, less the
/// room it has.
fn room_for<T>(list: &Vec<T>, more: usize) -> usize {
    let need = list.len() + more;
    if need <= list.capacity() {
        0
    } else {
        need.max(list.capacity() * 2).max(4) - list.capacity()
    }
}

/// Grows `list`, where it has no room for `more`, by what [`room_for`] says, asking for that room
/// exactly, so that what is held is what was counted before it was asked for.
fn make_room_for<T>(list: &mut Vec<T>, more: usize) {
    let grow = room_for(list, more);
    if grow > 0 {
        list.reserve_exact(list.capacity() + grow - list.len());
    }
}

/// Where the classes past a row lead from the kept sets that have been left by them: one table for
/// every kept set, looked up by the place a set's row begins and the class, so what it holds is the
/// steps worked out and no more. A slot is a key, the row above the class, or [`NO_STEP`], and the
/// place the row of the set the step leads to begins, or [`NONE`]; a step is looked for from the
/// slot its key names to the first empty one. Never more than half full.
struct Cold {
    keys: Vec<u64>,
    to: Vec<u32>,
    count: usize,
}

impl Cold {
    fn new() -> Cold {
        Cold {
            keys: Vec::new(),
            to: Vec::new(),
            count: 0,
        }
    }

    /// The room the table holds: each slot's key and where it leads, empty or not. A table is grown
    /// when it would be more than half full, so right after growing it is a quarter full, and a step
    /// it holds may be counted as up to four slots.
    fn held(&self) -> usize {
        self.keys.capacity() * size_of::<u64>() + self.to.capacity() * size_of::<u32>()
    }

    /// The room keeping one more step would add to [`Cold::held`]: what growing the table adds,
    /// where it would be more than half full, and otherwise nothing.
    fn room_for_one(&self) -> usize {
        if (self.count + 1) * 2 > self.keys.len() {
            let size = (self.keys.len() * 2).max(16);
            (size - self.keys.capacity()) * size_of::<u64>()
                + (size - self.to.capacity()) * size_of::<u32>()
        } else {
            0
        }
    }

    /// The slot a key is looked for from, of `size` slots.
    fn slot(key: u64, size: usize) -> usize {
        (key.wrapping_mul(0x9E37_79B9_7F4A_7C15) >> 32) as usize & (size - 1)
    }

    /// Where class `class` leads from the kept set whose row begins at `row`, or [`UNKNOWN`].
    fn get(&self, row: u32, class: usize) -> u32 {
        if self.keys.is_empty() {
            return UNKNOWN;
        }
        let key = (u64::from(row) << 32) | class as u64;
        let mask = self.keys.len() - 1;
        let mut at = Cold::slot(key, self.keys.len());
        while self.keys[at] != NO_STEP {
            if self.keys[at] == key {
                return self.to[at];
            }
            at = (at + 1) & mask;
        }
        UNKNOWN
    }

    /// Keeps that class `class` leads from the kept set whose row begins at `row` to `to`, which is
    /// not kept.
    fn insert(&mut self, row: u32, class: usize, to: u32) {
        if (self.count + 1) * 2 > self.keys.len() {
            self.grow();
        }
        let key = (u64::from(row) << 32) | class as u64;
        let mask = self.keys.len() - 1;
        let mut at = Cold::slot(key, self.keys.len());
        while self.keys[at] != NO_STEP {
            at = (at + 1) & mask;
        }
        self.keys[at] = key;
        self.to[at] = to;
        self.count += 1;
    }

    /// Twice the slots, or sixteen, in lists of that room, each step put in again by its key. What
    /// is gone over is the steps kept.
    fn grow(&mut self) {
        let size = (self.keys.len() * 2).max(16);
        let keys = core::mem::replace(&mut self.keys, Vec::with_capacity(size));
        let to = core::mem::replace(&mut self.to, Vec::with_capacity(size));
        for _ in 0..size {
            self.keys.push(NO_STEP);
            self.to.push(UNKNOWN);
        }
        let mask = size - 1;
        for each in 0..keys.len() {
            if keys[each] == NO_STEP {
                continue;
            }
            let mut at = Cold::slot(keys[each], size);
            while self.keys[at] != NO_STEP {
                at = (at + 1) & mask;
            }
            self.keys[at] = keys[each];
            self.to[at] = to[each];
        }
    }
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
/// set it copies, [`Cache::put`] over the slots it looks in and the row it makes, [`Cache::same`]
/// over a kept set, and
/// [`Cache::grow`] over the kept sets; [`Cold::get`] and [`Cold::insert`] over the slots they look
/// in, and [`Cold::grow`] over the steps kept; and [`Walk::next_set`], over every state, once in four
/// billion sets. Making room is not a loop here: `vec!` makes `entered` once, and a list grows in
/// `push` by doubling, as far as the most states the walk has held in it at once, as `Walk` says.
/// The test `the_walk_names_every_loop_it_has` holds this list to the functions with a loop in them.
pub(crate) fn matches(machine: &Machine, cache: &mut Cache, subject: &str) -> bool {
    walk_with(machine, cache, subject, &mut Keeping::new())
}

/// [`matches`], with what the match decides about keeping sets held in `keeping`.
fn walk_with(machine: &Machine, cache: &mut Cache, subject: &str, keeping: &mut Keeping) -> bool {
    let width = machine.classes.of_ascii();
    let mut i = 0;
    let mut at = match cache.start {
        Some(start) => start,
        None => {
            // Only a cache that has kept nothing comes here: forgetting the kept sets keeps this
            // one, which is kept whatever it costs.
            cache.walk.begin(machine);
            let start = cache.hold(machine, width, keeping);
            cache.start = Some(start);
            start
        }
    };
    loop {
        if at == NONE {
            return false;
        }
        let from = i;
        let stop = run_known(cache, width, &machine.classes, subject, &mut i, &mut at);
        // What is read is counted in bytes, which is all the choice to keep sets asks of it.
        keeping.read += i - from;
        let Some((class, c)) = stop else {
            return cache.accepting[at as usize / width];
        };
        if cache.step(width, at, class) == NONE {
            return false;
        }
        cache
            .walk
            .advance(machine, &cache.sets[at as usize / width], c);
        i += c.len_utf8();
        let row = at;
        // Forgetting the kept sets keeps the one a walk starts in, so a step from it is kept after,
        // with the set it leads to, whatever it costs.
        let from_start = cache.start == Some(row);
        at = match cache.come_to(machine, width, keeping) {
            // A frozen match writes no step. One that forgot has no row to write it in, but for the
            // set a walk starts in.
            Some((next, forgot)) if forgot || keeping.frozen => {
                if forgot && from_start {
                    cache.learn_from_start(width, class, next);
                }
                next
            }
            Some((next, _)) => {
                if cache.learn(width, row, class, next, false) || !cache.make_room(width, keeping) {
                    // Kept; or not, and the match is frozen, in the set it came to, which is kept.
                    next
                } else {
                    // The kept sets were forgotten for the step, and the set the walk came to is
                    // kept again, beside the one a walk starts in.
                    let next = cache.hold(machine, width, keeping);
                    if from_start {
                        cache.learn_from_start(width, class, next);
                    }
                    next
                }
            }
            // The walk goes on a state at a time from the set it came to, in `next`.
            None => match cache.walk_alone(machine, width, subject, &mut i) {
                Alone::Ended(answer) => return answer,
                Alone::Rejoined(row) => row,
            },
        };
    }
}

/// Reads `subject` from byte `i` by the steps `cache` keeps, from the kept set whose row begins at
/// `at`, moving both, until the end, where it answers `None`, or until a character whose step from
/// where it is is not one to a kept set, where it answers the character's class and the character,
/// neither read. The subject is read as every walk reads one ([`read_classes`]): an ASCII character
/// is a lookup of its class and one of its row's `next`, and only a class past the row asks `cold`.
fn run_known(
    cache: &Cache,
    width: usize,
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
        |row, class| cache.next[row as usize + class],
        |row, class| cache.step(width, row, class),
        |known| known >= NONE,
    );
    *at = row;
    stopped.map(|class| {
        (
            class,
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
        match cache.walk_alone(machine, machine.classes.of_ascii(), subject, &mut i) {
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
            cache.cold = Cold::new();
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
        let width = machine.classes.of_ascii();
        let mut matcher = pattern.matcher();
        let subject = Numbers(7).ab(400);
        matcher.matches(&subject);
        let cache = &mut matcher.cache;
        let sets = cache.sets.clone();
        assert!(sets.len() > 10, "{} sets kept", sets.len());
        for (at, set) in sets.into_iter().enumerate() {
            *cache = Cache::new();
            cache.walk.next_set(machine);
            for &q in &set {
                cache.walk.enter(machine, q);
            }
            let room = cache.walk.next.capacity();
            let kept = cache.keep(machine, width);
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
        assert!(keeping.restarted && keeping.frozen);
        // Forgetting the kept sets kept the one a walk starts in, so the next match starts in a kept
        // set and does not forget the others to keep it.
        let start = matcher
            .cache
            .start
            .expect("the set a walk starts in is kept");
        let width = machine(&pattern).classes.of_ascii();
        assert!(
            (start as usize) < matcher.cache.sets.len() * width,
            "the set a walk starts in is one of those kept"
        );
        let friendly = "ab".repeat(400);
        let (answer, keeping) = decided(&mut matcher, &friendly);
        assert_eq!(answer, seventeenth(&friendly));
        assert!(!keeping.frozen, "the next match kept sets");
        // The step from the set a walk starts in was forgotten with the others when the match before
        // forgot them, and is worked out once; the set it leads to is found kept.
        let (answer, keeping) = decided(&mut matcher, &friendly);
        assert_eq!(answer, seventeenth(&friendly));
        assert_eq!(keeping.made, 0, "no set made once they are kept");
        assert!(
            keeping.read + 1 >= friendly.len(),
            "{} of {} characters read by kept steps",
            keeping.read,
            friendly.len()
        );
        let (_, keeping) = decided(&mut matcher, &friendly);
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

    /// A set of 100,000 runs cuts the scalar values into 200,001 classes, and a kept set costs as
    /// much as it would were they a few: its row is as wide as the classes ASCII is in, and where
    /// the others lead is kept as each is read. The steps past the row are kept and read again by
    /// the next match as those in it are.
    #[test]
    fn what_a_kept_set_costs_does_not_grow_with_the_classes() {
        let mut text = String::from("[");
        for each in 0..100_000u32 {
            text.push(char::from_u32(0x10000 + 2 * each).expect("past the surrogates"));
        }
        text.push_str("]+");
        let pattern = pattern(&text);
        assert!(machine(&pattern).classes.count() > 200_000);
        let mut matcher = pattern.matcher();
        let subject: String = (0..50u32)
            .map(|each| char::from_u32(0x10000 + 2 * (each * 997 % 100_000)).expect("a scalar"))
            .collect();
        assert!(matcher.matches(&subject));
        assert!(!matcher.matches(&format!("{subject}a")));
        assert!(!matcher.matches("\u{10001}"));
        assert!(
            matcher.cache.held() < 4 << 10,
            "{} bytes kept",
            matcher.cache.held()
        );
        let (answer, keeping) = decided(&mut matcher, &subject);
        assert!(answer);
        assert_eq!(keeping.made, 0);
        assert_eq!(
            keeping.read,
            subject.len(),
            "every character read by kept steps"
        );
    }

    /// `n` characters, each α or β at random, whose classes are past the row, so that every step
    /// between the sets of [`greek`] is kept in `cold`.
    fn alpha_beta(numbers: &mut Numbers, n: usize) -> String {
        (0..n)
            .map(|_| if numbers.below(2) == 0 { 'α' } else { 'β' })
            .collect()
    }

    /// The ninth character from the end is α: a pattern of many sets, every step between them past
    /// the row.
    fn greek() -> crate::Pattern {
        pattern("(?:α|β)*α(?:α|β){8}")
    }

    /// Where there is no room to keep a step past the row, the match is told and decides, as for a
    /// set: here it forgets the kept sets once without asking, and keeps the step after. Before, the
    /// step was not kept and nothing said so: with the room full of sets whose steps were all
    /// known, a match of them worked out every step again, and so did every match after it, none of
    /// them coming to a new set that would have made room.
    #[test]
    fn a_step_there_is_no_room_for_is_decided_on_as_a_set_is() {
        let pattern = greek();
        let mut matcher = pattern.matcher();
        let subject = alpha_beta(&mut Numbers(3), 400);
        let (_, keeping) = decided(&mut matcher, &subject);
        assert!(!keeping.restarted, "the sets fit in the room");
        // Every set the subject comes to is kept; the steps between them are forgotten, and the
        // room is filled to a byte short of what it holds, so that no step fits.
        paths::forget_steps(&mut matcher.cache);
        let spare = KNOWN_BYTES - matcher.cache.room() - 1;
        let hashes = matcher.cache.hashes.len();
        let room = matcher.cache.hashes.capacity() - hashes;
        matcher.cache.hashes.reserve_exact(room + spare / 4);
        assert!(matcher.cache.room() + matcher.cache.cold.room_for_one() > KNOWN_BYTES);
        assert_eq!(matcher.cache.hashes.len(), hashes);
        let (answer, keeping) = decided(&mut matcher, &subject);
        assert_eq!(answer, subject.chars().rev().nth(8) == Some('α'));
        assert!(
            keeping.restarted && !keeping.frozen,
            "the match forgot the sets to keep the step"
        );
        assert!(matcher.cache.room() <= KNOWN_BYTES);
        // Every step was kept by the match that forgot the sets, the step from the set a walk
        // starts in with the set it led to.
        let (_, keeping) = decided(&mut matcher, &subject);
        assert_eq!(keeping.made, 0, "no set made once they are kept");
        assert_eq!(
            keeping.read,
            subject.len(),
            "every character read by kept steps"
        );
    }

    /// The set every match needs takes none of the room, so what is kept in the room is the other
    /// sets and the steps, past the row as in it. Every character from U+0100 to U+03FF leads the
    /// set the walk goes round back to itself: the set a walk starts in is kept beside the room, the
    /// one gone round and its 768 steps past the row in it, and the next match reads every
    /// character by kept steps. A set in Rust is never larger than the room, so this holds what is
    /// counted where, not a match that would have frozen.
    #[test]
    fn the_set_every_match_starts_in_takes_none_of_the_room() {
        let pattern = pattern("(?:[\u{100}-\u{3FF}]*){20000}");
        let mut matcher = pattern.matcher();
        let subject: String = (0x100..0x400u32)
            .chain(0x100..0x400u32)
            .map(|c| char::from_u32(c).expect("a scalar value"))
            .collect();
        let (answer, _) = decided(&mut matcher, &subject);
        assert!(answer);
        assert_eq!(matcher.cache.sets.len(), 2);
        let start = matcher.cache.sets[0].len() * size_of::<u32>();
        let gone_round = matcher.cache.sets[1].len() * size_of::<u32>();
        let beside = matcher.cache.held() - matcher.cache.room();
        assert!(
            beside >= start && beside < start + gone_round,
            "{beside} bytes beside the room, for a set of {start} and not one of {gone_round}"
        );
        assert!(matcher.cache.room() >= gone_round);
        let (_, keeping) = decided(&mut matcher, &subject);
        assert!(!keeping.frozen && keeping.made == 0);
        assert_eq!(
            keeping.read,
            subject.len(),
            "every character read by kept steps"
        );
    }

    /// What is kept is held to [`KNOWN_BYTES`], counted as the room the lists hold, whether it is
    /// sets with steps in their rows or steps past them in `cold`, across matches that fill the room
    /// and forget it; and the two sets kept whatever they cost, with the step between them, are the
    /// only room past it.
    #[test]
    fn what_is_kept_is_held_to_the_room_it_is_given() {
        let mut numbers = Numbers(5);
        for (pattern, subjects) in [
            (
                pattern("(?:a|b)*a(?:a|b){16}"),
                [numbers.ab(200_000), numbers.ab(20_000), "ab".repeat(400)],
            ),
            (
                greek(),
                [
                    alpha_beta(&mut numbers, 200_000),
                    alpha_beta(&mut numbers, 20_000),
                    "αβ".repeat(400),
                ],
            ),
        ] {
            let mut matcher = pattern.matcher();
            for subject in &subjects {
                matcher.matches(subject);
                assert!(
                    matcher.cache.room() <= KNOWN_BYTES,
                    "{} bytes held in the room",
                    matcher.cache.room()
                );
                assert!(
                    matcher.cache.held() - matcher.cache.room() <= 2 * 1024,
                    "{} bytes held beside it, for sets of a few states",
                    matcher.cache.held() - matcher.cache.room()
                );
            }
        }
    }
}
