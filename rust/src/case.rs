use alloc::string::String;

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
/// The text is read once, forward, and every step of a conversion is taken in this loop.
/// `Final_Sigma` holds of a sigma with a `Cased` code point before it and none after it, each looked
/// for past the `Case_Ignorable` code points between. What comes before is carried as the text is
/// read: whether the last code point that is not `Case_Ignorable` was `Cased`. What comes after is
/// not known yet where the sigma is read, so a sigma that has a cased code point before it is
/// pending: where its mapping goes in the answer is kept, the `Case_Ignorable` code points after it
/// are mapped, and the next code point that is not `Case_Ignorable`, or the end of the text, decides
/// which mapping it takes. Only one is pending at a time, since the code point that decides it is
/// read before another sigma could be.
///
/// What is bounded is what is written: each code point's mapping is counted before any of it is
/// written, a pending sigma's once it is decided, so the answer never holds more than `longest`.
/// Whether the whole answer is within `longest` does not depend on the order the mappings were
/// counted in.
fn map_case(text: &str, lower: bool, longest: usize) -> Option<String> {
    let mut out = String::with_capacity(text.len().min(longest));
    let mut written = 0usize;
    let mut cased_before = false;
    // The byte in `out` a pending sigma's mapping goes at, and the sigma.
    let mut pending: Option<(usize, char)> = None;
    for c in text.chars() {
        let ignorable = within(CASE_IGNORABLE, c);
        if !ignorable && let Some((at, sigma)) = pending.take() {
            settle(
                &mut out,
                &mut written,
                longest,
                at,
                sigma,
                !within(CASED, c),
            )?;
        }
        if lower && cased_before && mapped(FINAL_SIGMA, c).is_some() {
            pending = Some((out.len(), c));
        } else {
            let mapping = mapped(if lower { LOWER } else { UPPER }, c);
            put(&mut out, &mut written, longest, mapping, c)?;
        }
        if !ignorable {
            cased_before = within(CASED, c);
        }
    }
    if let Some((at, sigma)) = pending {
        settle(&mut out, &mut written, longest, at, sigma, true)?;
    }
    Some(out)
}

/// Writes `c`'s `mapping`, or `c` where it has none, at the end of `out`, or answers `None` where
/// that would take what is written past `longest`.
fn put(
    out: &mut String,
    written: &mut usize,
    longest: usize,
    mapping: Option<&[char]>,
    c: char,
) -> Option<()> {
    let adding = mapping.map_or(1, <[char]>::len);
    if adding > longest - *written {
        return None;
    }
    *written += adding;
    match mapping {
        Some(mapping) => out.extend(mapping),
        None => out.push(c),
    }
    Some(())
}

/// Writes a pending sigma's mapping at byte `at` of `out`, the final form's where `final_sigma`,
/// or answers `None` where that would take what is written past `longest`. What was written after
/// `at` is the `Case_Ignorable` code points that followed the sigma, so each byte of the answer is
/// moved by at most one sigma.
fn settle(
    out: &mut String,
    written: &mut usize,
    longest: usize,
    at: usize,
    sigma: char,
    final_sigma: bool,
) -> Option<()> {
    let mapping = if final_sigma {
        mapped(FINAL_SIGMA, sigma)
    } else {
        mapped(LOWER, sigma)
    };
    let mut put_here = String::new();
    let mut counted = 0;
    put(
        &mut put_here,
        &mut counted,
        longest - *written,
        mapping,
        sigma,
    )?;
    *written += counted;
    out.insert_str(at, &put_here);
    Some(())
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
