use alloc::string::{String, ToString};
use alloc::vec::Vec;

use crate::normalization_tables::{
    CANONICAL, COMBINING_CLASSES, COMPATIBILITY, NFC_TRIVIAL_LIMIT, NFD_TRIVIAL_LIMIT,
    NFKC_TRIVIAL_LIMIT, NFKD_TRIVIAL_LIMIT, SCRIPT_SPECIFIC_EXCLUSIONS,
};
use crate::tables::mapped;

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

    /// Text made only of code points below this is its own normalization in the form.
    fn trivial_limit(self) -> char {
        match self {
            Form::Nfc => NFC_TRIVIAL_LIMIT,
            Form::Nfd => NFD_TRIVIAL_LIMIT,
            Form::Nfkc => NFKC_TRIVIAL_LIMIT,
            Form::Nfkd => NFKD_TRIVIAL_LIMIT,
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
    normalize_within(form, text, usize::MAX)
        .expect("no text is longer than usize::MAX scalar values")
}

/// [`normalize`] where the answer is no longer than `longest` scalar values, and `None` where it is
/// longer, which is found out before more than `longest` is written.
///
/// Text made only of code points below the form's trivial limit is its own normalization, so it is
/// answered with itself, as long as it is. Other text is normalized from the last code point below
/// the limit before the first one that is not, and what comes before that is kept as it is. It is
/// its own normalization, and nothing from there on reaches back into it: the code point there is a
/// starter that composes with nothing before it, and a starter blocks every mark after it from
/// composing with a starter before it. That code point itself is normalized with the rest, since
/// what follows it may compose with it.
pub fn normalize_within(form: Form, text: &str, longest: usize) -> Option<String> {
    let limit = form.trivial_limit();
    let mut last = 0;
    let mut before_last = 0;
    let mut read = 0usize;
    for (at, c) in text.char_indices() {
        if c >= limit {
            return normalize_from(form, text, last, before_last, longest);
        }
        last = at;
        before_last = read;
        read += 1;
    }
    (read <= longest).then(|| text.to_string())
}

/// [`normalize_within`] worked out by the algorithm from byte `from` of `text`, taking the text
/// before it, `kept` scalar values long, as it is.
///
/// The three steps are taken one combining run at a time, as the text is read, in this loop and the
/// [`Composing`] it feeds; the scan for the trivial limit in [`normalize_within`] is the one other
/// place a code point is read, and the putting in order in [`Composing::settle`] the one place the
/// marks of a run are gone over. Each code point is decomposed as it arrives,
/// the marks after a starter are held until the next starter, and then they are put in canonical
/// order and, in a composing form, composed into it. Canonical ordering never moves a mark past a
/// starter, and composition joins a starter only to the marks after it or, where nothing is between
/// them, to the starter after it, so a run settled when the next starter arrives is settled as the
/// whole text's algorithm would settle it. What is held at once is one run's marks, never the
/// decomposition of the whole text.
fn normalize_from(
    form: Form,
    text: &str,
    from: usize,
    kept: usize,
    longest: usize,
) -> Option<String> {
    if kept > longest {
        return None;
    }
    let mut composing = Composing {
        composes: form.composes(),
        longest,
        out: String::with_capacity(text.len().min(longest)),
        written: kept,
        starter: None,
        marks: Vec::new(),
    };
    composing.out.push_str(&text[..from]);
    let mut parts = Vec::new();
    for c in text[from..].chars() {
        parts.clear();
        decompose_into(&mut parts, c, form.compatibility());
        for &part in &parts {
            composing.take(part)?;
        }
    }
    composing.finish()
}

/// One pass of canonical ordering and, where the form composes, composition over code points
/// already decomposed: the starter of the run it is in, the marks held after it, and what is
/// settled.
struct Composing {
    composes: bool,
    longest: usize,
    out: String,
    written: usize,
    starter: Option<char>,
    marks: Vec<char>,
}

impl Composing {
    /// Takes the next decomposed code point; `None` where what is written has passed `longest`.
    fn take(&mut self, c: char) -> Option<()> {
        if combining_class(c) != 0 {
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

    /// What is left, once the text has been read; `None` where it passes `longest`.
    fn finish(mut self) -> Option<String> {
        self.settle();
        self.write()?;
        Some(self.out)
    }

    /// Puts the held marks in canonical order and, where the form composes, composes into the
    /// starter each one nothing blocks; the marks left are those that did not compose.
    fn settle(&mut self) {
        // Stable, so marks of one class keep the order they came in.
        self.marks.sort_by_key(|mark| combining_class(*mark));
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

    /// Writes the starter and the marks after it, and empties the run.
    fn write(&mut self) -> Option<()> {
        let count = usize::from(self.starter.is_some()) + self.marks.len();
        if count > self.longest - self.written {
            return None;
        }
        self.written += count;
        self.out.extend(self.starter.take());
        self.out.extend(self.marks.drain(..));
        Some(())
    }
}

/// Unicode's canonical combining class of `c`: 0 for a starter, and for a mark the class canonical
/// ordering sorts it by. No Hangul jamo or syllable has one other than 0.
fn combining_class(c: char) -> u8 {
    COMBINING_CLASSES
        .binary_search_by_key(&c, |&(from, _)| from)
        .map_or(0, |at| COMBINING_CLASSES[at].1)
}

/// Pushes `c`'s full decomposition onto `parts`: Hangul's arithmetic, or the tables followed until
/// nothing decomposes further, the compatibility mappings as well as the canonical ones where
/// `compatibility`. A code point with none is its own.
fn decompose_into(parts: &mut Vec<char>, c: char, compatibility: bool) {
    if let Some(jamo) = decompose_hangul(c) {
        parts.extend(jamo.into_iter().flatten());
        return;
    }
    let mapping = mapped(CANONICAL, c).or_else(|| {
        if compatibility {
            mapped(COMPATIBILITY, c)
        } else {
            None
        }
    });
    match mapping {
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
/// [`COMBINING_CLASSES`] here, so decomposition and composition cannot disagree. Worked out when the
/// crate is compiled, from the generated tables.
static COMPOSITIONS: [(char, char, char); composition_count()] = compositions();

const fn composes_back(composite: char, mapping: &[char]) -> bool {
    if mapping.len() != 2 || const_combining_class(mapping[0]) != 0 {
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

const fn const_combining_class(c: char) -> u8 {
    let (mut low, mut high) = (0, COMBINING_CLASSES.len());
    while low < high {
        let mid = (low + high) / 2;
        if (COMBINING_CLASSES[mid].0 as u32) < c as u32 {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    if low < COMBINING_CLASSES.len() && COMBINING_CLASSES[low].0 as u32 == c as u32 {
        COMBINING_CLASSES[low].1
    } else {
        0
    }
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
