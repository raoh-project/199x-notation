//! The four forms against Unicode's own `NormalizationTest.txt`, in full.

mod common;

use common::{repository_file, shown};
use notation199x::{Form, append_normalized, normalize, normalize_within};

const FORMS: [Form; 4] = [Form::Nfc, Form::Nfd, Form::Nfkc, Form::Nfkd];

fn decode_hex(field: &str) -> String {
    field
        .split_whitespace()
        .map(|token| char::from_u32(u32::from_str_radix(token, 16).unwrap()).unwrap())
        .collect()
}

/// Each data line is five columns, source, NFC, NFD, NFKC and NFKD, and the file states what
/// conformance is: c2 == toNFC(c1) == toNFC(c2) == toNFC(c3) and c4 == toNFC(c4) == toNFC(c5);
/// c3 == toNFD(c1) == toNFD(c2) == toNFD(c3) and c5 == toNFD(c4) == toNFD(c5); c4 == toNFKC and
/// c5 == toNFKD of all five. A code point no line of Part 1 names is its own normalization in every
/// form.
#[test]
fn normalization_answers_every_line_of_the_conformance_test() {
    let Some(path) = repository_file("ucd/18.0.0/NormalizationTest.txt") else {
        return;
    };
    let data = std::fs::read_to_string(path).unwrap();
    assert!(
        data.starts_with("# NormalizationTest-18.0.0.txt\n"),
        "the file opens with another line"
    );
    let mut named = vec![false; 0x110000];
    let mut part_one = false;
    let mut checked = 0;
    let mut failures = Vec::new();
    for (n, line) in data.lines().enumerate() {
        let text = line.split('#').next().unwrap().trim();
        if let Some(part) = text.strip_prefix('@') {
            part_one = part == "Part1";
            continue;
        }
        if text.is_empty() {
            continue;
        }
        let columns: Vec<String> = text.split(';').take(5).map(decode_hex).collect();
        if part_one {
            named[u32::from(columns[0].chars().next().unwrap()) as usize] = true;
        }
        checked += 1;
        for (i, source) in columns.iter().enumerate() {
            let (nfc, nfd) = if i < 3 {
                (&columns[1], &columns[2])
            } else {
                (&columns[3], &columns[4])
            };
            for (form, want) in [
                (Form::Nfc, nfc),
                (Form::Nfd, nfd),
                (Form::Nfkc, &columns[3]),
                (Form::Nfkd, &columns[4]),
            ] {
                let got = normalize(form, source);
                if &got != want {
                    failures.push(format!(
                        "line {}: {form:?}(c{} {}) is {}, not {}",
                        n + 1,
                        i + 1,
                        shown(source),
                        shown(&got),
                        shown(want)
                    ));
                }
            }
        }
    }
    assert!(
        checked >= 10_000,
        "the file's data lines were read: {checked}"
    );
    for c in (char::MIN..=char::MAX).filter(|c| !named[u32::from(*c) as usize]) {
        let alone = c.to_string();
        for form in FORMS {
            if normalize(form, &alone) != alone {
                failures.push(format!(
                    "{form:?} changes U+{:04X}, which Part 1 does not name",
                    u32::from(c)
                ));
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} failed, the first:\n{}",
        failures.len(),
        failures[..failures.len().min(20)].join("\n")
    );
}

/// A text normalized one combining run at a time is the text normalized whole: a bound that is the
/// answer's length takes it, and one less does not.
#[test]
fn a_bound_is_held_on_the_answer() {
    for text in ["", "a", "Å", "Ǻ", "ﬃ", "㌀", "가", "\u{1D15E}", "ȩ́́x"] {
        for form in FORMS {
            let whole = normalize(form, text);
            let length = whole.chars().count();
            assert_eq!(
                normalize_within(form, text, length).as_deref(),
                Some(whole.as_str()),
                "{form:?} of {}",
                shown(text)
            );
            if length > 0 {
                assert_eq!(
                    normalize_within(form, text, length - 1),
                    None,
                    "{form:?} of {}",
                    shown(text)
                );
            }
        }
    }
}

/// Every text of the conformance file, cut in two at each place between its code points, each half
/// put in a form and the second appended to the first: what that answers is the form of the whole,
/// in every form. A cut falls between a starter and its marks, between two marks of different
/// classes, and between two starters that compose, which is where appending has work to do.
#[test]
fn appending_answers_the_form_of_the_whole_at_every_cut() {
    let Some(path) = repository_file("ucd/18.0.0/NormalizationTest.txt") else {
        return;
    };
    let data = std::fs::read_to_string(path).unwrap();
    let mut checked = 0;
    for line in data.lines() {
        let text = line.split('#').next().unwrap().trim();
        if text.is_empty() || text.starts_with('@') {
            continue;
        }
        for column in text.split(';').take(5).map(decode_hex) {
            let cuts: Vec<usize> = column.char_indices().map(|(at, _)| at).skip(1).collect();
            for form in FORMS {
                let whole = normalize(form, &column);
                for &cut in &cuts {
                    let mut joined = normalize(form, &column[..cut]);
                    append_normalized(form, &mut joined, &normalize(form, &column[cut..]));
                    assert_eq!(
                        joined,
                        whole,
                        "{form:?} of {} cut at byte {cut}",
                        shown(&column)
                    );
                    checked += 1;
                }
            }
        }
    }
    assert!(checked > 100_000, "{checked} cuts");
}

/// Texts of several pieces, each in a form, appended one at a time: what that answers is the form
/// of all of them joined. The pieces are drawn from what composes, reorders, is excluded from
/// composition and composes backwards: letters and marks of several classes, Hangul jamo and
/// syllables, starters that are the second of a pair, a composite that begins with one, and a mark
/// that decomposes to two.
#[test]
fn appending_piece_by_piece_answers_the_form_of_all_of_them() {
    let pool: [u32; 34] = [
        0x61, 0x65, 0x41, 0xe9, 0xc5, 0x3c9, 0x2126, 0x1100, 0x1161, 0x11a8, 0xac00, 0xac01, 0x301,
        0x302, 0x323, 0x308, 0x345, 0x304b, 0x304c, 0x3099, 0x915, 0x958, 0x93c, 0xb47, 0xb3e,
        0xb4b, 0xf71, 0xf72, 0xf73, 0x344, 0x1611e, 0x1611f, 0x16121, 0x16123,
    ];
    let mut seed: u64 = 0x2545_f491_4f6c_dd1d;
    let mut next = |bound: usize| {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        (seed % bound as u64) as usize
    };
    for _ in 0..5_000 {
        let pieces: Vec<String> = (0..1 + next(4))
            .map(|_| {
                (0..next(5))
                    .map(|_| char::from_u32(pool[next(pool.len())]).unwrap())
                    .collect()
            })
            .collect();
        for form in FORMS {
            let mut joined = String::new();
            let mut whole = String::new();
            for piece in &pieces {
                append_normalized(form, &mut joined, &normalize(form, piece));
                whole.push_str(piece);
            }
            assert_eq!(joined, normalize(form, &whole), "{form:?} of {pieces:?}");
        }
    }
}
