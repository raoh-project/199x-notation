use alloc::string::{String, ToString};
use alloc::vec::Vec;

use crate::normalization_tables::{
    CANONICAL, COMBINING_CLASS_BLOCKS, COMBINING_CLASS_PAGES, COMPATIBILITY,
    DECOMPOSITION_POSITION_BLOCKS, DECOMPOSITION_POSITION_PAGES, LONGEST_DECOMPOSITION,
    MOST_MARKS_COMPOSED, NFC_TRIVIAL_LIMIT, NFD_TRIVIAL_LIMIT, NFKC_TRIVIAL_LIMIT,
    NFKD_TRIVIAL_LIMIT, SCRIPT_SPECIFIC_EXCLUSIONS, STABLE_BLOCKS, STABLE_PAGES,
};

/// The four normalization forms of UAX #15.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Form {
    /// Canonical decomposition, then canonical composition.
    Nfc,
    /// Canonical decomposition.
    Nfd,
    /// Compatibility decomposition, then canonical composition.
    Nfkc,
    /// Compatibility decomposition.
    Nfkd,
}

impl Form {
    fn compatibility(self) -> bool {
        matches!(self, Form::Nfkc | Form::Nfkd)
    }

    fn composes(self) -> bool {
        matches!(self, Form::Nfc | Form::Nfkc)
    }

    /// Every code point below this is a stable starter of the form, which is known without asking a
    /// table.
    fn trivial_limit(self) -> char {
        match self {
            Form::Nfc => NFC_TRIVIAL_LIMIT,
            Form::Nfd => NFD_TRIVIAL_LIMIT,
            Form::Nfkc => NFKC_TRIVIAL_LIMIT,
            Form::Nfkd => NFKD_TRIVIAL_LIMIT,
        }
    }

    /// The form's bit of [`STABLE_PAGES`].
    fn stable_bit(self) -> u8 {
        match self {
            Form::Nfc => 1,
            Form::Nfd => 2,
            Form::Nfkc => 4,
            Form::Nfkd => 8,
        }
    }
}

/// `text` in `form`, by Unicode 18.0.0's tables.
///
/// The algorithm is the standard three steps of UAX #15: decompose fully, by the tables and by
/// Hangul's arithmetic, put combining marks in canonical order, and in a composing form compose
/// canonically wherever nothing blocks it. The compatibility forms decompose by the compatibility
/// mappings as well as the canonical ones; composition is canonical in every form.
pub fn normalize(form: Form, text: &str) -> String {
    normalized::<false>(form, text, usize::MAX)
        .expect("no text is longer than usize::MAX scalar values")
}

/// [`normalize`] where the answer is no longer than `longest` scalar values, and `None` where it is
/// longer, which is found out before more than `longest` is written. What it holds and how much of
/// `text` it reads turn on `longest` and not on the length of `text`: it reads no further once what
/// it has read shows the answer to be longer.
///
/// Text that is its own normalization is answered as it is. The text is read a code point at a time
/// and kept as it is up to the first code point that is not a stable starter of the form: a starter
/// whose quick check for the form is Yes ([`STABLE_PAGES`]). From the stable starter before that
/// one, which what follows it may compose with, the algorithm is run up to the next stable starter,
/// and the text is kept as it is again from there. A stable starter composes with nothing before it
/// and blocks every mark after it from reaching a starter before it, so what comes before it is
/// settled when it is read.
///
/// Two things are kept as the text is read: where the next run the algorithm goes over would begin,
/// the last stable starter read or, where none has been read since the last such run, where the text
/// is read to; and how many scalar values of the answer come before that. A run is written into the
/// answer only where it changed what it went over, and the answer is made only once one has: up to
/// there it is the text.
///
/// Each stable starter read is at least one scalar value of the answer, as the starter it is or
/// composed with what follows it, so the text is read no further once those read and those before
/// them are more than `longest`.
///
/// The text is gone over a code point at a time in [`stable_up_to`] and in [`Composing::run`], each
/// code point once by each at most, and the marks of a run, which may be as many as the text has, in
/// [`Composing::order`], [`Composing::settle`] and [`Composing::write`], a mark at a time; no other
/// loop turns on the text. The loop of [`normalized`] goes once round for each run the algorithm
/// goes over.
pub fn normalize_within(form: Form, text: &str, longest: usize) -> Option<String> {
    normalized::<true>(form, text, longest)
}

/// [`normalize_within`], which asks nothing of `longest` before it holds a mark where `BOUNDED` is
/// false: [`normalize`] does not pay for what only a bound needs.
fn normalized<const BOUNDED: bool>(form: Form, text: &str, longest: usize) -> Option<String> {
    // The answer up to `kept`, where a run has changed what it went over.
    let mut out: Option<String> = None;
    let mut kept = 0;
    let mut start = 0;
    // The scalar values of the answer before the last run the algorithm went over, and the stable
    // starters read since, the last of them at `start` where `start` is before `at`.
    let mut before = 0;
    let mut read = 0;
    let mut composing: Option<Composing<BOUNDED>> = None;
    let mut at = 0;
    while at < text.len() {
        // What is written and read is never more than `longest`, so this does not wrap.
        let (stable, count, last) = stable_up_to(form, text, at, longest - before - read);
        if stable > at {
            read += count;
            if before + read > longest {
                return None;
            }
            start = last;
            at = stable;
            if at == text.len() {
                break;
            }
        }
        if start < at {
            read -= 1;
        }
        let composing = composing.get_or_insert_with(|| Composing::new(form, longest));
        let end = composing.run(text, start, at, before + read)?;
        if composing.out != text[start..end] {
            let out = out.get_or_insert_with(|| String::with_capacity(text.len().min(longest)));
            out.push_str(&text[kept..start]);
            out.push_str(&composing.out);
            kept = end;
        }
        before = composing.written;
        read = 0;
        start = end;
        at = end;
    }
    Some(match out {
        None => text.to_string(),
        Some(mut out) => {
            out.push_str(&text[kept..]);
            out
        }
    })
}

/// Where the stable starters of `form` that `text` has from byte `at` end: the first code point from
/// there that is not one, or the end of the text, or where it has gone past one more than `left` of
/// them; how many it went past; and where the last of them begins, which is `at` where there are
/// none.
fn stable_up_to(form: Form, text: &str, mut at: usize, left: usize) -> (usize, usize, usize) {
    let limit = form.trivial_limit();
    let bit = form.stable_bit();
    let mut count = 0;
    let mut last = at;
    while at < text.len() && count <= left {
        let bytes = &text.as_bytes()[..scan_stop(text, at, left, count)];
        while at < bytes.len() {
            // Every form's trivial limit is past ASCII, so a byte below 0x80 is a stable starter.
            if bytes[at] < 0x80 {
                last = at;
                at += 1;
                count += 1;
                continue;
            }
            let c = text[at..]
                .chars()
                .next()
                .expect("a byte that is not ASCII begins a character here");
            if c >= limit && !is_stable(bit, c) {
                return (at, count, last);
            }
            last = at;
            at += c.len_utf8();
            count += 1;
        }
    }
    (at, count, last)
}

/// How far a scan of `text` from byte `at`, which may go past one more than `left` code points and
/// has gone past `count`, can go without counting them: a code point is a byte or more, so up to
/// there it goes past no more than that, and a code point past ASCII can leave it short, where the
/// scan goes on from there. What is left is held against the bytes left before anything is added
/// to `at`, so that no bound, however large, takes it past the end.
pub(crate) fn scan_stop(text: &str, at: usize, left: usize, count: usize) -> usize {
    if left - count >= text.len() - at {
        text.len()
    } else {
        at + left - count + 1
    }
}

/// Whether `c` is a stable starter of the form whose bit of [`STABLE_PAGES`] is `bit`.
fn is_stable(bit: u8, c: char) -> bool {
    let c = c as usize;
    STABLE_PAGES[usize::from(STABLE_BLOCKS[c >> 8]) << 8 | c & 0xFF] & bit != 0
}

/// [`normalize_within`] worked out by the algorithm over the whole of `text`, whatever it is.
#[cfg(test)]
fn normalize_whole(form: Form, text: &str, longest: usize) -> Option<String> {
    let mut composing = Composing::<true>::new(form, longest);
    composing.run(text, 0, text.len(), 0)?;
    Some(composing.out)
}

/// `next` appended to `text`, and the two in `form`, where each already is: the text [`normalize`]
/// answers for the two joined, worked out from where they meet.
///
/// Normalization is not closed under joining: a mark at the start of `next` may be ordered before
/// one at the end of `text`, or compose with a starter there. What joining can change is only
/// where the two meet, from the last starter of `text` to the first code point of `next` that
/// nothing before it reaches: a starter whose decomposition begins with a starter, and in a
/// composing form one where neither it nor that first member composes with a starter before it.
/// The first member is asked as well, because a composite of a composing form may begin with
/// what composes backwards: `U+16123` is `U+1611E U+1611F`, and `U+1611E` composes with a
/// `U+1611E` before it. Only that is normalized again, and the rest of either is copied, so a caller that
/// builds a text a piece at a time spends what copying the pieces does and not what normalizing
/// all of them again for each piece would.
///
/// Where `text` or `next` is not in `form`, what `text` becomes is in no form this promises.
pub fn append_normalized(form: Form, text: &mut String, next: &str) {
    let reach = next
        .char_indices()
        .find(|&(_, c)| out_of_reach(form, c))
        .map_or(next.len(), |(at, _)| at);
    if reach == 0 {
        text.push_str(next);
        return;
    }
    let from = text
        .char_indices()
        .rev()
        .find(|&(_, c)| combining_class(c) == 0)
        .map_or(0, |(at, _)| at);
    let mut seam = text.split_off(from);
    seam.push_str(&next[..reach]);
    text.push_str(&normalize(form, &seam));
    text.push_str(&next[reach..]);
}

/// Whether nothing before `c` in a text in `form` is changed by what comes after `c`, nor `c` by
/// what comes before it. See [`append_normalized`].
fn out_of_reach(form: Form, c: char) -> bool {
    // Below U+0300 no code point is a mark, the second of a pair, or decomposes to either, which a
    // test holds of every one of them; most of what is joined starts there.
    if c < FIRST_IN_REACH {
        return true;
    }
    out_of_reach_by_the_tables(form, c)
}

/// Every code point below this is out of reach in every form, as [`out_of_reach_by_the_tables`]
/// answers.
const FIRST_IN_REACH: char = '\u{0300}';

/// [`out_of_reach`] answered from the tables alone.
fn out_of_reach_by_the_tables(form: Form, c: char) -> bool {
    let head = canonical_head(c);
    combining_class(c) == 0
        && combining_class(head) == 0
        && !(form.composes() && (composes_back_into(c) || composes_back_into(head)))
}

/// The first code point of `c`'s full canonical decomposition, which is `c` where it has none.
fn canonical_head(c: char) -> char {
    if let Some([Some(leading), ..]) = decompose_hangul(c) {
        return leading;
    }
    match decomposition_of(c, false) {
        Some(mapping) => canonical_head(mapping[0]),
        None => c,
    }
}

/// Whether a starter before `c` may compose with it: `c` is the second of a pair
/// [`COMPOSITIONS`] holds, or a Hangul vowel or trailing consonant.
fn composes_back_into(c: char) -> bool {
    let c32 = u32::from(c);
    (V_BASE..V_BASE + V_COUNT).contains(&c32)
        || (T_BASE + 1..T_BASE + T_COUNT).contains(&c32)
        || SECONDS.binary_search(&c).is_ok()
}

/// One pass of canonical ordering and, where the form composes, composition over code points
/// already decomposed: the starter of the run it is in, the marks held after it, and what is
/// settled.
///
/// The three steps are taken one combining run at a time, as the text is read: each code point is
/// decomposed as it arrives, the marks after a starter are held until the next starter, and then
/// they are put in canonical order and, in a composing form, composed into it. Canonical ordering
/// never moves a mark past a starter, and composition joins a starter only to the marks after it
/// or, where nothing is between them, to the starter after it, so a run settled when the next
/// starter arrives is settled as the whole text's algorithm would settle it. What is held at once
/// is one run's marks, never the decomposition of the whole text.
///
/// What is written and the least of the answer what is held can come to are no more than
/// `longest`, which [`Composing::take`] holds to before it holds another mark: the starter, and the
/// marks but those it may compose with, which are no more than [`MOST_MARKS_COMPOSED`]. So a run
/// holds no more than `longest` and [`MOST_MARKS_COMPOSED`] marks, whatever the length of the text.
/// Where `BOUNDED` is false, `longest` is `usize::MAX`, which no text reaches, and it is not asked.
struct Composing<const BOUNDED: bool> {
    compatibility: bool,
    composes: bool,
    stable_bit: u8,
    longest: usize,
    /// What [`Composing::run`] wrote, the run settled.
    out: String,
    /// How many scalar values of the answer come before what is held: those before the run, and
    /// those it has written.
    written: usize,
    starter: Option<char>,
    marks: Vec<char>,
    /// Where a long run's marks are put in order, kept between runs.
    ordered: Vec<char>,
    /// What one code point decomposes into, kept between code points.
    parts: Vec<char>,
}

impl<const BOUNDED: bool> Composing<BOUNDED> {
    fn new(form: Form, longest: usize) -> Composing<BOUNDED> {
        Composing {
            compatibility: form.compatibility(),
            composes: form.composes(),
            stable_bit: form.stable_bit(),
            longest,
            out: String::new(),
            written: 0,
            starter: None,
            marks: Vec::new(),
            ordered: Vec::new(),
            parts: Vec::with_capacity(LONGEST_DECOMPOSITION),
        }
    }

    /// Runs the algorithm over `text` from byte `start`, `before` scalar values of the answer coming
    /// before it, up to the first stable starter after byte `at`, or to the end of the text where
    /// none comes, and writes the run settled into `out`. The code points from `start` to `at`, and
    /// the one there, are taken whatever they are; with `at` the end of the text, all of it from
    /// `start` is. Answers where it stopped, or `None` where the answer has passed `longest`.
    fn run(&mut self, text: &str, start: usize, at: usize, before: usize) -> Option<usize> {
        self.out.clear();
        self.written = before;
        let mut end = text.len();
        for (offset, c) in text[start..].char_indices() {
            if start + offset > at && is_stable(self.stable_bit, c) {
                end = start + offset;
                break;
            }
            self.parts.clear();
            decompose_into(&mut self.parts, c, self.compatibility);
            for i in 0..self.parts.len() {
                self.take(self.parts[i])?;
            }
        }
        self.settle();
        self.write()?;
        Some(end)
    }

    /// Takes the next decomposed code point; `None` where what is written has passed `longest`.
    fn take(&mut self, c: char) -> Option<()> {
        if combining_class(c) != 0 {
            if BOUNDED && self.least_held(self.marks.len() + 1) > self.longest - self.written {
                return None;
            }
            self.marks.push(c);
            return Some(());
        }
        self.settle();
        if self.composes
            && self.marks.is_empty()
            && let Some(composed) = self.starter.and_then(|starter| compose(starter, c))
        {
            self.starter = Some(composed);
            return Some(());
        }
        self.write()?;
        self.starter = Some(c);
        Some(())
    }

    /// The least number of scalar values of the answer the starter held and `marks` marks after it
    /// come to, whatever follows: every mark, and the starter, but those of the marks it may compose
    /// with in a composing form.
    fn least_held(&self, marks: usize) -> usize {
        if self.starter.is_none() {
            return marks;
        }
        if self.composes {
            return 1 + marks.saturating_sub(MOST_MARKS_COMPOSED);
        }
        1 + marks
    }

    /// Puts the held marks in canonical order and, where the form composes, composes into the
    /// starter each one nothing blocks; the marks left are those that did not compose. Each loop
    /// here goes over the marks of the run one at a time.
    fn settle(&mut self) {
        if self.marks.len() > 1 {
            self.order();
        }
        let Some(mut starter) = self.starter.filter(|_| self.composes) else {
            return;
        };
        let mut kept = 0;
        // A mark is blocked from the starter by a mark left between them of a class not below its
        // own; the marks are in order, so that is one of the same class.
        let mut last_class = None;
        for i in 0..self.marks.len() {
            let mark = self.marks[i];
            let class = combining_class(mark);
            match compose(starter, mark).filter(|_| last_class.is_none_or(|last| last < class)) {
                Some(composed) => starter = composed,
                None => {
                    self.marks[kept] = mark;
                    kept += 1;
                    last_class = Some(class);
                }
            }
        }
        self.marks.truncate(kept);
        self.starter = Some(starter);
    }

    /// Canonical ordering of the held marks: stable, by combining class. A run may be as long as the
    /// text, so a long one is put in order by counting its classes, of which there are 256 at most,
    /// in time linear in the run; a short one by insertion, which costs less there.
    fn order(&mut self) {
        let count = self.marks.len();
        if count <= FEW_MARKS {
            for i in 1..count {
                let mark = self.marks[i];
                let class = combining_class(mark);
                let mut j = i;
                while j > 0 && combining_class(self.marks[j - 1]) > class {
                    self.marks[j] = self.marks[j - 1];
                    j -= 1;
                }
                self.marks[j] = mark;
            }
            return;
        }
        let mut starts = [0usize; 257];
        for i in 0..count {
            starts[usize::from(combining_class(self.marks[i])) + 1] += 1;
        }
        for class in 1..starts.len() {
            starts[class] += starts[class - 1];
        }
        self.ordered.clear();
        for _ in 0..count {
            self.ordered.push('\0');
        }
        for i in 0..count {
            let mark = self.marks[i];
            let place = &mut starts[usize::from(combining_class(mark))];
            self.ordered[*place] = mark;
            *place += 1;
        }
        core::mem::swap(&mut self.marks, &mut self.ordered);
    }

    /// Writes the starter and the marks after it, one at a time, and empties the run.
    fn write(&mut self) -> Option<()> {
        if let Some(starter) = self.starter.take() {
            self.write_one(starter)?;
        }
        for i in 0..self.marks.len() {
            self.write_one(self.marks[i])?;
        }
        self.marks.clear();
        Some(())
    }

    fn write_one(&mut self, c: char) -> Option<()> {
        if self.written >= self.longest {
            return None;
        }
        self.out.push(c);
        self.written += 1;
        Some(())
    }
}

/// How many marks a run may hold before they are put in order by counting rather than by
/// insertion, which is quadratic in the run.
const FEW_MARKS: usize = 32;

/// Unicode's canonical combining class of `c`: 0 for a starter, and for a mark the class canonical
/// ordering sorts it by. No Hangul jamo or syllable has one other than 0.
const fn combining_class(c: char) -> u8 {
    let c = c as usize;
    COMBINING_CLASS_PAGES[(COMBINING_CLASS_BLOCKS[c >> 8] as usize) << 8 | c & 0xFF]
}

/// `c`'s one-step decomposition by [`CANONICAL`], or where `compatibility` by [`COMPATIBILITY`] as
/// well, read where [`DECOMPOSITION_POSITION_PAGES`] says it is; `None` where it has none.
fn decomposition_of(c: char, compatibility: bool) -> Option<&'static [char]> {
    let cp = c as usize;
    let at = usize::from(
        DECOMPOSITION_POSITION_PAGES
            [usize::from(DECOMPOSITION_POSITION_BLOCKS[cp >> 8]) << 8 | cp & 0xFF],
    );
    match at {
        0 => None,
        at if at <= CANONICAL.len() => Some(CANONICAL[at - 1].1),
        at if compatibility => Some(COMPATIBILITY[at - CANONICAL.len() - 1].1),
        _ => None,
    }
}

/// Pushes `c`'s full decomposition onto `parts`: Hangul's arithmetic, or the tables followed until
/// nothing decomposes further, the compatibility mappings as well as the canonical ones where
/// `compatibility`. A code point with none is its own.
fn decompose_into(parts: &mut Vec<char>, c: char, compatibility: bool) {
    if let Some(jamo) = decompose_hangul(c) {
        parts.extend(jamo.into_iter().flatten());
        return;
    }
    match decomposition_of(c, compatibility) {
        Some(mapping) => {
            for &part in mapping {
                decompose_into(parts, part, compatibility);
            }
        }
        None => parts.push(c),
    }
}

// Hangul's algorithmic decomposition and composition (UAX #15, the Hangul section).
const S_BASE: u32 = 0xAC00;
const L_BASE: u32 = 0x1100;
const V_BASE: u32 = 0x1161;
const T_BASE: u32 = 0x11A7;
const L_COUNT: u32 = 19;
const V_COUNT: u32 = 21;
const T_COUNT: u32 = 28;
const N_COUNT: u32 = V_COUNT * T_COUNT;
const S_COUNT: u32 = L_COUNT * N_COUNT;

fn hangul(value: u32) -> char {
    char::from_u32(value).expect("a Hangul syllable or jamo is a scalar value")
}

/// A Hangul syllable's L, V and, where it has one, T jamo.
fn decompose_hangul(c: char) -> Option<[Option<char>; 3]> {
    let index = u32::from(c)
        .checked_sub(S_BASE)
        .filter(|index| *index < S_COUNT)?;
    let t = index % T_COUNT;
    Some([
        Some(hangul(L_BASE + index / N_COUNT)),
        Some(hangul(V_BASE + (index % N_COUNT) / T_COUNT)),
        (t != 0).then(|| hangul(T_BASE + t)),
    ])
}

/// The primary composite of `starter` followed by `c`, or `None` where the pair does not compose:
/// Hangul's L+V and LV+T, or [`COMPOSITIONS`].
fn compose(starter: char, c: char) -> Option<char> {
    let (s, c32) = (u32::from(starter), u32::from(c));
    if (L_BASE..L_BASE + L_COUNT).contains(&s) && (V_BASE..V_BASE + V_COUNT).contains(&c32) {
        return Some(hangul(
            S_BASE + ((s - L_BASE) * V_COUNT + (c32 - V_BASE)) * T_COUNT,
        ));
    }
    if (S_BASE..S_BASE + S_COUNT).contains(&s)
        && (s - S_BASE).is_multiple_of(T_COUNT)
        && (T_BASE + 1..T_BASE + T_COUNT).contains(&c32)
    {
        return Some(hangul(s + (c32 - T_BASE)));
    }
    COMPOSITIONS
        .binary_search_by_key(&(starter, c), |&(first, second, _)| (first, second))
        .ok()
        .map(|at| COMPOSITIONS[at].2)
}

/// Every pair that composes, as `(first, second, composite)`, sorted by the pair: every two-member
/// canonical decomposition whose first member is a starter and whose composite is not one of
/// [`SCRIPT_SPECIFIC_EXCLUSIONS`]. The singleton decompositions and those whose first member is not a
/// starter, the rest of `Full_Composition_Exclusion`, are read off [`CANONICAL`] and
/// [`COMBINING_CLASS_PAGES`] here, so decomposition and composition cannot disagree. Worked out when
/// the crate is compiled, from the generated tables.
static COMPOSITIONS: [(char, char, char); composition_count()] = COMPOSITIONS_AT_COMPILE;

/// The second member of every pair in [`COMPOSITIONS`], sorted, a member as often as it is one.
static SECONDS: [char; composition_count()] = seconds();

const fn seconds() -> [char; composition_count()] {
    let mut seconds = ['\0'; composition_count()];
    let mut n = 0;
    while n < COMPOSITIONS_AT_COMPILE.len() {
        let second = COMPOSITIONS_AT_COMPILE[n].1;
        let mut j = n;
        while j > 0 && seconds[j - 1] as u32 > second as u32 {
            seconds[j] = seconds[j - 1];
            j -= 1;
        }
        seconds[j] = second;
        n += 1;
    }
    seconds
}

/// [`COMPOSITIONS`] as a constant, which a constant can be worked out from where a static cannot.
const COMPOSITIONS_AT_COMPILE: [(char, char, char); composition_count()] = compositions();

const fn composes_back(composite: char, mapping: &[char]) -> bool {
    if mapping.len() != 2 || combining_class(mapping[0]) != 0 {
        return false;
    }
    let mut i = 0;
    while i < SCRIPT_SPECIFIC_EXCLUSIONS.len() {
        if SCRIPT_SPECIFIC_EXCLUSIONS[i] as u32 == composite as u32 {
            return false;
        }
        i += 1;
    }
    true
}

const fn composition_count() -> usize {
    let mut count = 0;
    let mut i = 0;
    while i < CANONICAL.len() {
        if composes_back(CANONICAL[i].0, CANONICAL[i].1) {
            count += 1;
        }
        i += 1;
    }
    count
}

const fn compositions() -> [(char, char, char); composition_count()] {
    let mut pairs = [('\0', '\0', '\0'); composition_count()];
    let mut n = 0;
    let mut i = 0;
    while i < CANONICAL.len() {
        let (composite, mapping) = CANONICAL[i];
        if composes_back(composite, mapping) {
            // Insertion by the pair; the decompositions come sorted by composite, which is close to
            // sorted by first member.
            let key = (mapping[0] as u64) << 32 | mapping[1] as u64;
            let mut j = n;
            while j > 0 && ((pairs[j - 1].0 as u64) << 32 | pairs[j - 1].1 as u64) > key {
                pairs[j] = pairs[j - 1];
                j -= 1;
            }
            pairs[j] = (mapping[0], mapping[1], composite);
            n += 1;
        }
        i += 1;
    }
    pairs
}

#[cfg(test)]
mod tests {
    extern crate std;

    use super::*;
    use crate::tables::mapped;
    use std::format;
    use std::vec;

    const FORMS: [Form; 4] = [Form::Nfc, Form::Nfd, Form::Nfkc, Form::Nfkd];

    /// `name` under the repository's `ucd/18.0.0`, or `None` where it is not there and the
    /// environment does not require it, as the tests under `tests/` read the repository's files.
    fn ucd(name: &str) -> Option<String> {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../ucd/18.0.0")
            .join(name);
        match std::fs::read_to_string(&path) {
            Ok(text) => Some(text),
            Err(_) if std::env::var_os("NOTATION199X_REQUIRE_SUITE").is_none() => None,
            Err(error) => panic!("{}: {error}", path.display()),
        }
    }

    /// The first and last code point of a field of a UCD line, `XXXX` or `XXXX..YYYY`.
    fn range(field: &str) -> (u32, u32) {
        let field = field.trim();
        match field.split_once("..") {
            Some((first, last)) => (
                u32::from_str_radix(first, 16).unwrap(),
                u32::from_str_radix(last, 16).unwrap(),
            ),
            None => {
                let only = u32::from_str_radix(field, 16).unwrap();
                (only, only)
            }
        }
    }

    /// The combining class of every code point and the forms it is a stable starter in are what
    /// `UnicodeData.txt` and `DerivedNormalizationProps.txt` state: a stable starter of a form is a
    /// code point whose class is 0 and whose quick check for the form is Yes. The tables are held to
    /// the database by a reading of it of their own, rather than taken on the generator's word.
    #[test]
    fn the_paged_tables_are_what_the_database_states() {
        let (Some(unicode_data), Some(props)) =
            (ucd("UnicodeData.txt"), ucd("DerivedNormalizationProps.txt"))
        else {
            return;
        };
        let mut classes = vec![0u8; 0x110000];
        for line in unicode_data.lines() {
            let fields: Vec<&str> = line.split(';').collect();
            classes[u32::from_str_radix(fields[0], 16).unwrap() as usize] =
                fields[3].parse().unwrap();
        }
        let mut not_yes = vec![0u8; 0x110000];
        for line in props.lines() {
            let fields: Vec<&str> = line.split('#').next().unwrap().split(';').collect();
            if fields.len() != 3 || fields[2].trim() == "Y" {
                continue;
            }
            let bit = match fields[1].trim() {
                "NFC_QC" => 1,
                "NFD_QC" => 2,
                "NFKC_QC" => 4,
                "NFKD_QC" => 8,
                _ => continue,
            };
            let (first, last) = range(fields[0]);
            for cp in first..=last {
                not_yes[cp as usize] |= bit;
            }
        }
        for c in (0..0x110000).filter_map(char::from_u32) {
            let cp = c as usize;
            assert_eq!(combining_class(c), classes[cp], "{c:?}");
            for form in FORMS {
                let stated = classes[cp] == 0 && not_yes[cp] & form.stable_bit() == 0;
                assert_eq!(is_stable(form.stable_bit(), c), stated, "{c:?} in {form:?}");
            }
            // The table of where each decomposition is answers what the searched mappings do.
            let canonical = mapped(CANONICAL, c);
            assert_eq!(decomposition_of(c, false), canonical, "{c:?}");
            assert_eq!(
                decomposition_of(c, true),
                canonical.or_else(|| mapped(COMPATIBILITY, c)),
                "{c:?} with compatibility"
            );
        }
    }

    /// Texts that mix stable starters of several scripts with what is not one, so that a text goes
    /// in and out of the algorithm many times, normalized run by run as [`normalize_within`] does
    /// and by the algorithm over the whole text, alike in every form and within every bound around
    /// the answer's length.
    #[test]
    fn run_by_run_is_the_algorithm_over_the_whole_text_in_every_form() {
        let alphabet = [
            'a',
            'e',
            'A',
            ' ',
            '.',
            '\u{00E9}',
            '\u{00C7}', // stable in a canonical form
            '\u{3042}',
            '\u{304B}',
            '\u{30AB}',
            '\u{65E5}',
            '\u{672C}', // kana and ideographs
            '\u{AC00}',
            '\u{AC01}',
            '\u{D55C}', // Hangul syllables
            '\u{20B9F}',
            '\u{1F600}', // stable past the basic plane
            '\u{0300}',
            '\u{0301}',
            '\u{0323}',
            '\u{0327}',
            '\u{05B0}', // marks
            '\u{3099}',
            '\u{309A}',
            '\u{309B}', // kana voicing marks
            '\u{1100}',
            '\u{1161}',
            '\u{11A8}', // Hangul L, V and T
            '\u{FB01}',
            '\u{3231}',
            '\u{FF76}',
            '\u{FF9E}',
            '\u{00A0}',
            '\u{2126}', // compatibility, a singleton
            '\u{0B47}',
            '\u{0B3E}',
            '\u{1D15E}',
            '\u{0344}', // a starter second, marks that decompose
        ];
        let mut seed = 1999u64;
        let mut next = |bound: usize| {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            (seed >> 33) as usize % bound
        };
        for _ in 0..20_000 {
            let mut text = String::new();
            for _ in 0..next(40) {
                let c = alphabet[next(alphabet.len())];
                let times = if next(4) == 0 { 1 + next(6) } else { 1 };
                for _ in 0..times {
                    text.push(c);
                }
            }
            for form in FORMS {
                let whole = normalize_whole(form, &text, usize::MAX).unwrap();
                assert_eq!(normalize(form, &text), whole, "{form:?} {text:?}");
                let length = whole.chars().count();
                for longest in length.saturating_sub(2)..=length + 1 {
                    let within = normalize_within(form, &text, longest);
                    assert_eq!(
                        within.as_deref(),
                        (longest >= length).then_some(whole.as_str()),
                        "{form:?} {text:?} within {longest}"
                    );
                }
            }
        }
        // A text the algorithm changes in one place keeps the rest as it is.
        let japanese = "日本語のテキスト、ガギグ。".repeat(10);
        assert_eq!(
            normalize(Form::Nfc, &format!("{japanese}か\u{3099}{japanese}")),
            format!("{japanese}が{japanese}")
        );
    }

    /// What [`out_of_reach`] answers without asking the tables is what the tables answer.
    #[test]
    fn every_code_point_below_the_first_in_reach_is_out_of_reach() {
        for c in '\0'..FIRST_IN_REACH {
            for form in [Form::Nfc, Form::Nfd, Form::Nfkc, Form::Nfkd] {
                assert!(out_of_reach_by_the_tables(form, c), "{c:?} in {form:?}");
            }
        }
    }

    /// The room a decomposition is made with is the longest decomposition there is, in any form.
    #[test]
    fn the_room_for_a_decomposition_is_the_longest_there_is() {
        let mut parts = Vec::new();
        let mut longest = 0;
        for c in '\0'..=char::MAX {
            parts.clear();
            decompose_into(&mut parts, c, true);
            longest = longest.max(parts.len());
        }
        assert_eq!(longest, LONGEST_DECOMPOSITION);
    }

    /// Runs of every length from short to long, of marks of several classes: put in order by
    /// insertion or by counting, they are in the order a stable sort by class puts them in.
    #[test]
    fn a_run_of_any_length_is_put_in_canonical_order() {
        let marks = [
            '\u{0301}',
            '\u{0327}',
            '\u{0316}',
            '\u{0345}',
            '\u{05B0}',
            '\u{0300}',
            '\u{1D167}',
        ];
        let mut seed = 1u64;
        for length in 2..200 {
            let mut composing = Composing::<false>::new(Form::Nfd, usize::MAX);
            for _ in 0..length {
                seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
                composing
                    .marks
                    .push(marks[(seed >> 33) as usize % marks.len()]);
            }
            let mut expected = composing.marks.clone();
            expected.sort_by_key(|mark| combining_class(*mark));
            composing.order();
            assert_eq!(composing.marks, expected, "{length} marks");
        }
    }
}
