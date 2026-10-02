//! Reading an image of P1, as `image/P1.md` in the repository defines it.

use alloc::collections::BTreeSet;
use alloc::vec::Vec;

use super::machine::Machine;
use super::symbols::Symbols;

/// Text that is not an image of P1.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct NotAnImage;

impl core::fmt::Display for NotAnImage {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("not an image of P1")
    }
}

impl core::error::Error for NotAnImage {}

/// The machine `image` is an image of, or [`NotAnImage`] where it is not one.
///
/// Every rule `P1.md` gives is asked before anything is matched, so a machine that was read answers
/// every text it is asked about. Nothing is made ready for a count before what it counts has been
/// read: a count may be as large as any number, and the image holds as many of what it counts as
/// the count says or is not one, so what is made grows with what has been read.
pub(crate) fn read(image: &str) -> Result<Machine, NotAnImage> {
    let mut numbers = Numbers {
        text: image.as_bytes(),
        at: 0,
    };
    if !image.starts_with("P1") {
        return Err(NotAnImage);
    }
    numbers.at = 2;
    let deterministic = numbers.flag()?;
    let set_count = numbers.next()?;
    let mut sets = Vec::new();
    for _ in 0..set_count {
        sets.push(numbers.set()?);
    }
    let state_count = numbers.next()?;
    if state_count == 0 {
        return Err(NotAnImage);
    }
    let mut accepting = Vec::new();
    let mut steps = Vec::new();
    let mut free = Vec::new();
    for state in 0..state_count {
        accepting.push(numbers.flag()?);
        for _ in 0..numbers.next()? {
            let set = numbers.below(set_count)?;
            steps.push((state, set, numbers.below(state_count)?));
        }
        for _ in 0..numbers.next()? {
            free.push((state, numbers.below(state_count)?));
        }
    }
    if !numbers.done() {
        return Err(NotAnImage);
    }
    let machine = Machine::new(sets, accepting, &steps, &free);
    if deterministic && (!free.is_empty() || !one_way(&machine)) {
        return Err(NotAnImage);
    }
    Ok(machine)
}

/// The numbers of an image after its marker, each read after its comma.
struct Numbers<'a> {
    text: &'a [u8],
    at: usize,
}

impl Numbers<'_> {
    /// The next number: 0, or decimal digits that do not begin with 0, no greater than
    /// 2,147,483,647.
    fn next(&mut self) -> Result<u32, NotAnImage> {
        if self.text.get(self.at) != Some(&b',') {
            return Err(NotAnImage);
        }
        self.at += 1;
        let from = self.at;
        let mut value: u32 = 0;
        while let Some(digit @ b'0'..=b'9') = self.text.get(self.at).copied() {
            if self.at > from && self.text[from] == b'0' {
                return Err(NotAnImage);
            }
            value = value
                .checked_mul(10)
                .and_then(|v| v.checked_add(u32::from(digit - b'0')))
                .ok_or(NotAnImage)?;
            if value > i32::MAX as u32 {
                return Err(NotAnImage);
            }
            self.at += 1;
        }
        if self.at == from {
            return Err(NotAnImage);
        }
        Ok(value)
    }

    /// The next number, which is 0 or 1.
    fn flag(&mut self) -> Result<bool, NotAnImage> {
        match self.next()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(NotAnImage),
        }
    }

    /// The next number, which names one of `count` sets or states.
    fn below(&mut self, count: u32) -> Result<u32, NotAnImage> {
        Some(self.next()?).filter(|n| *n < count).ok_or(NotAnImage)
    }

    /// A set: its number of runs, and each run as its first and last scalar value. The runs are of
    /// scalar values, in order, and do not overlap; two may be next to each other.
    fn set(&mut self) -> Result<Symbols, NotAnImage> {
        let mut runs: Vec<(char, char)> = Vec::new();
        for _ in 0..self.next()? {
            let first = self.next()?;
            let last = self.next()?;
            // Of scalar values, and no surrogate between the ends.
            let (Some(first_char), Some(last_char)) = (char::from_u32(first), char::from_u32(last))
            else {
                return Err(NotAnImage);
            };
            if first > last || (first < 0xD800 && last > 0xDFFF) {
                return Err(NotAnImage);
            }
            if runs
                .last()
                .is_some_and(|&(_, previous)| previous >= first_char)
            {
                return Err(NotAnImage);
            }
            runs.push((first_char, last_char));
        }
        Ok(Symbols::in_order(runs))
    }

    fn done(&self) -> bool {
        self.at == self.text.len()
    }
}

/// Whether no two steps out of one state are over sets that have a scalar value in common.
///
/// A state stepping over fewer than two sets steps one way. Of the rest, states that step over the
/// same sets are asked about once, and the sets of a state are held against each other without
/// going over the widest of them: each run of the others is looked for in it by a search. So what
/// this looks at is, once for each different group of sets a state steps over, the runs of all but
/// the widest of them. That is not bounded by the image: a set is written once however many states
/// step over it, which is what `P1.md` asks and Java has always held an image to.
fn one_way(machine: &Machine) -> bool {
    let mut asked: BTreeSet<Vec<u32>> = BTreeSet::new();
    let mut others: Vec<(char, char)> = Vec::new();
    for q in 0..machine.states() {
        let steps = machine.steps_from(q);
        if steps.len() < 2 {
            continue;
        }
        let mut group: Vec<u32> = steps.iter().map(|&(set, _)| set).collect();
        group.sort_unstable();
        if asked.contains(&group) {
            continue;
        }
        let runs = |set: u32| machine.sets[set as usize].runs();
        // Two steps over one set that holds a scalar value step two ways for it.
        if group
            .windows(2)
            .any(|pair| pair[0] == pair[1] && !runs(pair[0]).is_empty())
        {
            return false;
        }
        let widest = *group
            .iter()
            .max_by_key(|&&set| runs(set).len())
            .expect("a group holds two sets or more");
        others.clear();
        let mut skipped = false;
        for &set in &group {
            if set == widest && !skipped {
                skipped = true;
                continue;
            }
            others.extend_from_slice(runs(set));
        }
        others.sort_unstable();
        let wide = &machine.sets[widest as usize];
        for (at, &(first, last)) in others.iter().enumerate() {
            if at > 0 && first <= others[at - 1].1 {
                return false;
            }
            if wide.meets(first, last) {
                return false;
            }
        }
        asked.insert(group);
    }
    true
}
