use alloc::boxed::Box;
use alloc::vec;
use alloc::vec::Vec;

use super::subject::read_classes;

/// The most entries a table of where each class leads from each state may hold, as many as Java
/// gives one: a machine past it is walked over its spans.
const MOST_ENTRIES: usize = 1 << 18;

/// Where a walk goes from a state no walk is accepted from, in [`ClassRows::table`].
const DEAD: u32 = u32::MAX;

/// A deterministic machine as its classes and rows, as an image of P2 writes it: the scalar values
/// cut into pieces, each in a class, and each state's row as spans of classes, each leading to one
/// state. Every scalar value is in one piece and every class in one span of each state, which the
/// reader held the image to, so a walk is one state at a time and every character leads somewhere.
/// Java holds a deterministic machine the same way, under the same name.
///
/// What is held is as large as the image, and what is made from it to walk faster is bounded by it
/// or by [`MOST_ENTRIES`]: the class of each ASCII character, which states a walk can still be
/// accepted from, and, where the states times the classes are within [`MOST_ENTRIES`], a table of
/// where each class leads from each state. A machine past that is walked over its spans, a scalar
/// value two searches.
pub(crate) struct ClassRows {
    /// Where each piece ends, ascending, the last at U+10FFFF, and the class of each.
    lasts: Vec<u32>,
    classes: Vec<u32>,
    accepting: Vec<bool>,
    /// The spans of state `q` are `ends[starts[q]..starts[q + 1]]`, each the last class it covers,
    /// ascending, and `to` at the same places, the state each leads to.
    starts: Vec<usize>,
    ends: Vec<u32>,
    to: Vec<u32>,
    /// The class of each ASCII character.
    ascii: Box<[usize; 128]>,
    /// Whether a walk from each state can still end at one that accepts.
    live: Vec<bool>,
    /// Where class `k` leads from the state whose row begins at `r` is `table[r + k]`: the place
    /// the row of that state begins, or [`DEAD`] where no walk is accepted from it. Empty where the
    /// table would be past [`MOST_ENTRIES`].
    table: Vec<u32>,
    width: usize,
}

impl ClassRows {
    /// The machine of these pieces and spans, as the reader read them, with `width` classes.
    pub(crate) fn new(
        lasts: Vec<u32>,
        classes: Vec<u32>,
        accepting: Vec<bool>,
        starts: Vec<usize>,
        ends: Vec<u32>,
        to: Vec<u32>,
        width: usize,
    ) -> ClassRows {
        let mut ascii = Box::new([0; 128]);
        let mut piece = 0;
        for (c, class) in ascii.iter_mut().enumerate() {
            while lasts[piece] < c as u32 {
                piece += 1;
            }
            *class = classes[piece] as usize;
        }
        let live = live(&accepting, &starts, &to);
        let mut rows = ClassRows {
            lasts,
            classes,
            accepting,
            starts,
            ends,
            to,
            ascii,
            live,
            table: Vec::new(),
            width,
        };
        let states = rows.accepting.len();
        if states
            .checked_mul(width)
            .is_some_and(|entries| entries <= MOST_ENTRIES)
        {
            let mut table = vec![DEAD; states * width];
            for q in 0..states {
                let mut class = 0;
                for span in rows.starts[q]..rows.starts[q + 1] {
                    let leads = rows.to[span] as usize;
                    let entry = if rows.live[leads] {
                        (leads * width) as u32
                    } else {
                        DEAD
                    };
                    while class <= rows.ends[span] as usize {
                        table[q * width + class] = entry;
                        class += 1;
                    }
                }
            }
            rows.table = table;
        }
        rows
    }

    /// The class of `c`, which is past ASCII: that of the first piece that ends at it or after.
    fn class_of(&self, c: char) -> usize {
        self.classes[self.lasts.partition_point(|&last| last < u32::from(c))] as usize
    }

    /// Whether the whole of `subject` is accepted: the one walk from state 0, a class at a time,
    /// which stops at a state no walk is accepted from. The subject is read as every walk reads one
    /// ([`read_classes`]); where a class leads is a lookup in the [`ClassRows::table`] where there
    /// is one, and otherwise a search of the state's spans.
    pub(crate) fn matches(&self, subject: &str) -> bool {
        if !self.live[0] {
            return false;
        }
        let mut at = 0;
        if self.table.is_empty() {
            let mut q = 0;
            let next = |q: u32, class| {
                let (from, to) = (self.starts[q as usize], self.starts[q as usize + 1]);
                let span = self.ends[from..to].partition_point(|&end| (end as usize) < class);
                self.to[from + span]
            };
            let stopped = read_classes(
                subject,
                &mut at,
                &self.ascii,
                |c| self.class_of(c),
                &mut q,
                next,
                next,
                |q| !self.live[q as usize],
            );
            return stopped.is_none() && self.accepting[q as usize];
        }
        let table = &self.table[..];
        let mut row = 0;
        let stopped = read_classes(
            subject,
            &mut at,
            &self.ascii,
            |c| self.class_of(c),
            &mut row,
            |row, class| table[row as usize + class],
            |row, class| table[row as usize + class],
            |row| row == DEAD,
        );
        stopped.is_none() && self.accepting[row as usize / self.width]
    }
}

/// Which states reach one that accepts, walked back from those: as long as the spans.
fn live(accepting: &[bool], starts: &[usize], to: &[u32]) -> Vec<bool> {
    let states = accepting.len();
    // Who leads into each state, grouped by that state.
    let mut into_starts = vec![0usize; states + 1];
    for &leads in to {
        into_starts[leads as usize + 1] += 1;
    }
    for q in 0..states {
        into_starts[q + 1] += into_starts[q];
    }
    let mut placed = into_starts.clone();
    let mut into = vec![0u32; to.len()];
    for q in 0..states {
        for &leads in &to[starts[q]..starts[q + 1]] {
            into[placed[leads as usize]] = q as u32;
            placed[leads as usize] += 1;
        }
    }
    let mut live = accepting.to_vec();
    let mut pending: Vec<u32> = (0..states as u32).filter(|&q| live[q as usize]).collect();
    while let Some(q) = pending.pop() {
        for &from in &into[into_starts[q as usize]..into_starts[q as usize + 1]] {
            if !live[from as usize] {
                live[from as usize] = true;
                pending.push(from);
            }
        }
    }
    live
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pattern::image::{Read, read};
    use alloc::string::String;

    /// The machine `image` writes, read twice: once walked over its table and once over its spans.
    fn both(image: &str) -> (ClassRows, ClassRows) {
        let (Ok(Read::Rows(table)), Ok(Read::Rows(mut spans))) = (read(image), read(image)) else {
            panic!("{image} is an image of P2");
        };
        assert!(!table.table.is_empty(), "{image} has a table");
        spans.table.clear();
        (table, spans)
    }

    /// A walk over the spans answers what a walk over the table does, for subjects of the
    /// characters each machine tells apart and others, ASCII and past it.
    #[test]
    fn a_walk_over_the_spans_answers_as_over_the_table() {
        let images = [
            // [a-z]+@[a-z]+\.[a-z]{2,}
            "P2,45,0,46,1,63,0,64,2,96,0,122,3,1114111,0,8,0,2,1,3,2,0,3,1,0,1,1,2,3,3,2,0,2,1,3,4,0,0,1,1,5,2,1,3,4,0,2,1,3,6,0,2,1,3,7,1,2,1,3,7",
            // [ぁ-んァ-ン一-龯]+
            "P2,12352,0,12435,1,12448,0,12531,1,19967,0,40879,1,1114111,0,3,0,0,1,1,2,0,1,1,1,0,1,1,2",
            // A class on both sides of the surrogates, and one past them.
            "P2,57344,0,1114111,1,3,0,0,1,1,2,1,1,2,0,1,2",
        ];
        let symbols = [
            'a', 'z', '@', '.', '-', '1', 'ぁ', 'ア', '一', '\u{D7FF}', '\u{E000}', '😀',
        ];
        let mut seed: u64 = 27;
        for image in images {
            let (table, spans) = both(image);
            for _ in 0..2_000 {
                let mut subject = String::new();
                seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
                for _ in 0..(seed >> 60) {
                    seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
                    subject.push(symbols[(seed >> 33) as usize % symbols.len()]);
                }
                assert_eq!(
                    table.matches(&subject),
                    spans.matches(&subject),
                    "{image} against {subject:?}"
                );
            }
        }
    }
}
