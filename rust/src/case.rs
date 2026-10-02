use alloc::string::String;
use alloc::vec::Vec;

use crate::case_tables::{CASE_IGNORABLE, CASED, FINAL_SIGMA, LOWER, UPPER};
use crate::tables::{mapped, within};

/// `text` in lowercase: Unicode 18.0.0's untailored full mapping, from `UnicodeData.txt` and
/// `SpecialCasing.txt`, with no locale or language tailoring. One code point can map to several. A
/// Greek capital sigma becomes the final form only at the end of a cased run, which is the one
/// condition the default mapping carries that is context rather than locale: `lowercase("ΟΣ")` is
/// `"ος"` and `lowercase("ΟΣΑ")` is `"οσα"`.
///
/// Not [`str::to_lowercase`], which maps by the Unicode version of the Rust release.
///
/// A case mapping is not closed under any normalization form, so a caller that holds its text in
/// one normalizes the answer.
pub fn lowercase(text: &str) -> String {
    map_case(text, true, usize::MAX).expect("no text is longer than usize::MAX scalar values")
}

/// `text` in uppercase, by the same untailored full mapping as [`lowercase`]: one code point can
/// widen to several, so `uppercase("straße")` is `"STRASSE"`, and no locale narrows it, so a
/// Turkish `i` still becomes `I`.
pub fn uppercase(text: &str) -> String {
    map_case(text, false, usize::MAX).expect("no text is longer than usize::MAX scalar values")
}

/// [`lowercase`] where that is no longer than `longest` scalar values, and `None` where it is
/// longer, which is found out before more than `longest` is written.
pub fn lowercase_within(text: &str, longest: usize) -> Option<String> {
    map_case(text, true, longest)
}

/// [`uppercase`] where that is no longer than `longest` scalar values, and `None` where it is
/// longer, which is found out before more than `longest` is written.
pub fn uppercase_within(text: &str, longest: usize) -> Option<String> {
    map_case(text, false, longest)
}

/// The mapped text, or `None` where it is longer than `longest`.
///
/// The text is read once, forward, and the conversion goes over it in this loop, one code point at
/// a time; the one other loop, in [`Mapped::settle`], goes over code points this one has read.
/// `Final_Sigma` holds of a sigma with a `Cased` code point before it and none after it, each looked
/// for past the `Case_Ignorable` code points between. What comes before is carried as the text is
/// read: whether the last code point that is not `Case_Ignorable` was `Cased`. What comes after is
/// not known yet where the sigma is read, so a sigma that has a cased code point before it is
/// pending, and the `Case_Ignorable` code points after it are mapped and held rather than written,
/// until the next code point that is not `Case_Ignorable`, or the end of the text, decides which
/// mapping the sigma takes; then the sigma's mapping is written and what was held after it. Only one
/// is pending at a time, since the code point that decides it is read before another sigma could be.
///
/// What is bounded is what is written and held: each code point's mapping is counted before any of
/// it is, a pending sigma's once it is decided, so the answer never holds more than `longest`.
/// Whether the whole answer is within `longest` does not depend on the order the mappings were
/// counted in.
fn map_case(text: &str, lower: bool, longest: usize) -> Option<String> {
    let mut mapped_text = Mapped {
        out: String::with_capacity(text.len().min(longest)),
        written: 0,
        longest,
        pending: None,
        held: Vec::new(),
    };
    let mut cased_before = false;
    for c in text.chars() {
        let ignorable = within(CASE_IGNORABLE, c);
        if !ignorable && let Some(sigma) = mapped_text.pending.take() {
            mapped_text.settle(sigma, !within(CASED, c))?;
        }
        if lower && cased_before && mapped(FINAL_SIGMA, c).is_some() {
            mapped_text.pending = Some(c);
        } else {
            mapped_text.put(mapped(if lower { LOWER } else { UPPER }, c), c)?;
        }
        if !ignorable {
            cased_before = within(CASED, c);
        }
    }
    if let Some(sigma) = mapped_text.pending.take() {
        mapped_text.settle(sigma, true)?;
    }
    Some(mapped_text.out)
}

/// The answer as it is written: what is written, how many scalar values that and what is held come
/// to, a sigma whose mapping is not decided yet, and the mappings of the code points after it,
/// held until it is.
struct Mapped {
    out: String,
    written: usize,
    longest: usize,
    pending: Option<char>,
    held: Vec<char>,
}

impl Mapped {
    /// Counts `c`'s `mapping`, or `c` where it has none, and writes it, or holds it where a sigma is
    /// pending; `None` where that would take the answer past `longest`.
    fn put(&mut self, mapping: Option<&[char]>, c: char) -> Option<()> {
        let adding = mapping.map_or(1, <[char]>::len);
        if adding > self.longest - self.written {
            return None;
        }
        self.written += adding;
        // A mapping is at most a few code points, as the table holds it.
        let mapped = mapping.unwrap_or(core::slice::from_ref(&c));
        if self.pending.is_some() {
            self.held.extend_from_slice(mapped);
        } else {
            self.out.extend(mapped);
        }
        Some(())
    }

    /// Writes a pending sigma's mapping, the final form's where `final_sigma`, and then what was
    /// held after it one code point at a time; `None` where the sigma's mapping would take the
    /// answer past `longest`.
    fn settle(&mut self, sigma: char, final_sigma: bool) -> Option<()> {
        let mapping = if final_sigma {
            mapped(FINAL_SIGMA, sigma)
        } else {
            mapped(LOWER, sigma)
        };
        self.put(mapping, sigma)?;
        for i in 0..self.held.len() {
            self.out.push(self.held[i]);
        }
        self.held.clear();
        Some(())
    }
}

#[cfg(test)]
mod tests {
    extern crate std;

    use super::*;
    use alloc::vec::Vec;

    /// `Final_Sigma` as Java decides it: from each sigma, look back and then forward past the
    /// `Case_Ignorable` code points.
    fn final_sigma_by_looking_around(text: &[char], at: usize) -> bool {
        let before = text[..at]
            .iter()
            .rev()
            .find(|c| !within(CASE_IGNORABLE, **c));
        let after = text[at + 1..].iter().find(|c| !within(CASE_IGNORABLE, **c));
        before.is_some_and(|c| within(CASED, *c)) && !after.is_some_and(|c| within(CASED, *c))
    }

    fn lowercase_by_looking_around(text: &[char]) -> String {
        let mut out = String::new();
        for (at, c) in text.iter().enumerate() {
            let final_form =
                mapped(FINAL_SIGMA, *c).filter(|_| final_sigma_by_looking_around(text, at));
            match final_form.or_else(|| mapped(LOWER, *c)) {
                Some(mapping) => out.extend(mapping),
                None => out.push(*c),
            }
        }
        out
    }

    /// Every text of up to six code points over a cased letter, a sigma, a `Case_Ignorable` code
    /// point, one that is both `Cased` and `Case_Ignorable`, one that is neither, and one that maps
    /// to two, lowercased in one pass and by looking around each sigma, alike and within every bound.
    #[test]
    fn one_pass_decides_final_sigma_as_looking_around_each_does() {
        let alphabet = ['A', 'Σ', '.', '\u{02B0}', ' ', '\u{0130}'];
        assert!(within(CASED, '\u{02B0}') && within(CASE_IGNORABLE, '\u{02B0}'));
        assert!(within(CASE_IGNORABLE, '.') && !within(CASED, '.'));
        let mut text: Vec<char> = Vec::new();
        for length in 0..=6u32 {
            for mut n in 0..alphabet.len().pow(length) {
                text.clear();
                for _ in 0..length {
                    text.push(alphabet[n % alphabet.len()]);
                    n /= alphabet.len();
                }
                let s: String = text.iter().collect();
                let expected = lowercase_by_looking_around(&text);
                assert_eq!(lowercase(&s), expected, "{s:?}");
                let length = expected.chars().count();
                for bound in 0..=length + 1 {
                    let within_bound = lowercase_within(&s, bound);
                    assert_eq!(
                        within_bound.is_some(),
                        bound >= length,
                        "{s:?} within {bound}"
                    );
                    if let Some(answer) = within_bound {
                        assert_eq!(answer, expected);
                    }
                }
            }
        }
    }
}
