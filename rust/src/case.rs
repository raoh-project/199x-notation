use alloc::string::{String, ToString};

use crate::case_tables::{
    CASE_IGNORABLE, CASED, FINAL_SIGMA, LOWER, LOWER_POSITION_BLOCKS, LOWER_POSITION_PAGES, UPPER,
    UPPER_POSITION_BLOCKS, UPPER_POSITION_PAGES,
};
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
/// Text that maps to itself is answered as it is. Otherwise what maps to itself is copied a run at
/// a time, from `kept`, and the answer is made only once a code point that changes is met. Whether
/// one does is read off [`LOWER_POSITION_PAGES`] or [`UPPER_POSITION_PAGES`], which answer where its
/// mapping is as well, and for ASCII off [`ASCII`], read off the same tables.
///
/// `Final_Sigma` holds of a sigma with a `Cased` code point before it and none after it, each looked
/// for past the `Case_Ignorable` code points between, in the text, by [`is_final_sigma`]. A sigma is
/// not `Case_Ignorable`, so what one sigma looks past another does not, and the looking around all
/// the sigmas of a text goes over each code point at most twice.
///
/// The text is gone over a code point at a time in [`same_up_to`] and in this loop, each code point
/// by one of them, and around a sigma in [`is_final_sigma`]; no other loop turns on the text.
///
/// What is bounded is what is written: each code point's mapping is counted before any of it is
/// written, so the answer never holds more than `longest`, nor part of a mapping that would take it
/// past.
fn map_case(text: &str, lower: bool, longest: usize) -> Option<String> {
    let (blocks, pages, mappings, ascii) = if lower {
        (
            &LOWER_POSITION_BLOCKS,
            &LOWER_POSITION_PAGES[..],
            LOWER,
            &ASCII[0],
        )
    } else {
        (
            &UPPER_POSITION_BLOCKS,
            &UPPER_POSITION_PAGES[..],
            UPPER,
            &ASCII[1],
        )
    };
    let bytes = text.as_bytes();
    // The answer up to `kept`, where a code point that changes has been met.
    let mut out: Option<String> = None;
    let mut kept = 0;
    let mut written: usize = 0;
    let mut at = 0;
    while at < bytes.len() {
        let (same, count) = same_up_to(text, at, ascii, blocks, pages);
        written += count;
        if written > longest {
            return None;
        }
        at = same;
        if at == bytes.len() {
            break;
        }
        let out = out.get_or_insert_with(|| String::with_capacity(text.len().min(longest)));
        out.push_str(&text[kept..at]);
        if bytes[at] < 0x80 && ascii[usize::from(bytes[at])] >= 0 {
            // The ASCII from here that changes is written as it is mapped, a character at a time.
            loop {
                if written == longest {
                    return None;
                }
                written += 1;
                out.push(char::from(ascii[usize::from(bytes[at])] as u8));
                at += 1;
                if at == bytes.len()
                    || bytes[at] >= 0x80
                    || ascii[usize::from(bytes[at])] < 0
                    || ascii[usize::from(bytes[at])] == i16::from(bytes[at])
                {
                    break;
                }
            }
            kept = at;
            continue;
        }
        let c = text[at..]
            .chars()
            .next()
            .expect("same_up_to stops where a character begins");
        let after = at + c.len_utf8();
        let mut mapping = mappings[usize::from(position(blocks, pages, c)) - 1].1;
        if lower
            && let Some(final_mapping) = mapped(FINAL_SIGMA, c)
            && is_final_sigma(text, at, after)
        {
            mapping = final_mapping;
        }
        if mapping.len() > longest - written {
            return None;
        }
        written += mapping.len();
        out.extend(mapping);
        kept = after;
        at = after;
    }
    Some(match out {
        None => text.to_string(),
        Some(mut out) => {
            out.push_str(&text[kept..]);
            out
        }
    })
}

/// Where the code points `text` has from byte `at` that the mapping leaves as they are end: the
/// first one from there that it changes, or the end of the text; and how many they are.
fn same_up_to(
    text: &str,
    mut at: usize,
    ascii: &[i16; 128],
    blocks: &[u8; 4352],
    pages: &[u16],
) -> (usize, usize) {
    let bytes = text.as_bytes();
    let mut count = 0;
    while at < bytes.len() {
        let byte = bytes[at];
        if byte < 0x80 {
            if ascii[usize::from(byte)] != i16::from(byte) {
                break;
            }
            at += 1;
            count += 1;
            continue;
        }
        let c = text[at..]
            .chars()
            .next()
            .expect("a byte that is not ASCII begins a character here");
        if position(blocks, pages, c) != 0 {
            break;
        }
        at += c.len_utf8();
        count += 1;
    }
    (at, count)
}

/// `c`'s value in a position table: 0 where the mapping leaves it as it is, and otherwise one more
/// than where its mapping is.
const fn position(blocks: &[u8; 4352], pages: &[u16], c: char) -> u16 {
    let c = c as usize;
    pages[(blocks[c >> 8] as usize) << 8 | c & 0xFF]
}

/// For the lowercase and the uppercase mapping, what each ASCII character maps to where the mapping
/// makes it one ASCII character and no `Final_Sigma` entry names it, and -1 where the tables are
/// asked. Read off the tables when the crate is compiled, so that most text is mapped a byte at a
/// time without a rule of its own about ASCII.
static ASCII: [[i16; 128]; 2] = [
    ascii_of(&LOWER_POSITION_BLOCKS, &LOWER_POSITION_PAGES, LOWER, true),
    ascii_of(&UPPER_POSITION_BLOCKS, &UPPER_POSITION_PAGES, UPPER, false),
];

const fn ascii_of(
    blocks: &[u8; 4352],
    pages: &[u16],
    mappings: &[(char, &[char])],
    lower: bool,
) -> [i16; 128] {
    let mut out = [-1; 128];
    let mut byte = 0;
    while byte < 128 {
        let c = byte as u8 as char;
        let at = position(blocks, pages, c);
        let mut final_sigma = false;
        let mut i = 0;
        while lower && i < FINAL_SIGMA.len() {
            if FINAL_SIGMA[i].0 as u32 == c as u32 {
                final_sigma = true;
            }
            i += 1;
        }
        if !final_sigma {
            if at == 0 {
                out[byte] = byte as i16;
            } else {
                let mapping = mappings[at as usize - 1].1;
                if mapping.len() == 1 && (mapping[0] as u32) < 0x80 {
                    out[byte] = mapping[0] as i16;
                }
            }
        }
        byte += 1;
    }
    out
}

/// Unicode's `Final_Sigma` condition of the code point between bytes `at` and `after`: preceded,
/// skipping `Case_Ignorable` code points, by a `Cased` one, and not followed, skipping the same way,
/// by another `Cased` one. Looked for as far as the text goes rather than over a window, since what
/// is skipped is decided by the property and not by a count.
fn is_final_sigma(text: &str, at: usize, after: usize) -> bool {
    let before = text[..at]
        .chars()
        .rev()
        .find(|c| !within(CASE_IGNORABLE, *c));
    if !before.is_some_and(|c| within(CASED, c)) {
        return false;
    }
    let next = text[after..].chars().find(|c| !within(CASE_IGNORABLE, *c));
    !next.is_some_and(|c| within(CASED, c))
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
    /// to two, lowercased and by mapping each code point by the searched tables and looking around
    /// each sigma, alike and within every bound.
    /// Each position table answers 0 where the searched mapping has nothing for a code point or
    /// maps it to itself, and otherwise one more than where the searched mapping holds it; and no
    /// code point `FINAL_SIGMA` names is 0.
    #[test]
    fn a_position_table_answers_where_the_mapping_holds_what_changes() {
        for (blocks, pages, mapping) in [
            (&LOWER_POSITION_BLOCKS, &LOWER_POSITION_PAGES[..], LOWER),
            (&UPPER_POSITION_BLOCKS, &UPPER_POSITION_PAGES[..], UPPER),
        ] {
            for c in (0..0x110000).filter_map(char::from_u32) {
                let index = mapping.binary_search_by_key(&c, |&(from, _)| from);
                let expected = match index {
                    Ok(at) if mapping[at].1 != [c] => at + 1,
                    _ => 0,
                };
                assert_eq!(usize::from(position(blocks, pages, c)), expected, "{c:?}");
            }
        }
        for &(c, _) in FINAL_SIGMA {
            assert_ne!(
                position(&LOWER_POSITION_BLOCKS, &LOWER_POSITION_PAGES, c),
                0,
                "{c:?}"
            );
        }
    }

    #[test]
    fn lowercase_is_the_mapping_with_final_sigma_looked_for_around_each_sigma() {
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
