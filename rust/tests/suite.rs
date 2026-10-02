//! The implementation held to every line of the repository's `suite` directory.

mod common;

use common::{each_line, shown};
use notation199x::*;
use std::cmp::Ordering;
use std::collections::HashSet;

#[test]
fn white_space_is_the_set_the_suite_lists() {
    let mut listed = HashSet::new();
    let mut read = false;
    each_line("suite/white-space.txt", 1, |line| {
        listed.insert(line.scalar(0));
        read = true;
        None
    });
    if !read {
        return;
    }
    let wrong: Vec<String> = (char::MIN..=char::MAX)
        .filter(|c| is_white_space(*c) != listed.contains(c))
        .map(|c| {
            format!(
                "U+{:04X} is white space: {}, and the suite lists it: {}",
                u32::from(c),
                is_white_space(c),
                listed.contains(&c)
            )
        })
        .collect();
    assert!(wrong.is_empty(), "{}", wrong.join("\n"));
}

#[test]
fn scalar_length_answers_every_line_of_the_suite() {
    each_line("suite/scalar-length.txt", 2, |line| {
        let text = line.text(0);
        let length = line.number(1);
        (scalar_count(&text) != length).then(|| {
            format!(
                "{} is {} long, not {length}",
                shown(&text),
                scalar_count(&text)
            )
        })
    });
}

#[test]
fn scalar_order_answers_every_line_of_the_suite() {
    each_line("suite/scalar-order.txt", 3, |line| {
        let a = line.text(0);
        let b = line.text(1);
        let order = match line.one_of(2, &["LESS", "EQUAL", "GREATER"]) {
            "LESS" => Ordering::Less,
            "EQUAL" => Ordering::Equal,
            _ => Ordering::Greater,
        };
        (compare(&a, &b) != order || compare(&b, &a) != order.reverse()).then(|| {
            format!(
                "{} against {} is {:?}, not {order:?}",
                shown(&a),
                shown(&b),
                compare(&a, &b)
            )
        })
    });
}

#[test]
fn case_conversion_answers_every_line_of_the_suite() {
    each_line("suite/case.txt", 5, |line| {
        let text = line.text(0);
        let lower = line.one_of(1, &["LOWER", "UPPER"]) == "LOWER";
        let bound = line.number_or_nothing(2);
        let past = line.one_of(3, &["MAPPED", "PAST"]) == "PAST";
        let mapped = if past {
            line.empty(4);
            None
        } else {
            Some(line.text(4))
        };
        let answer = match (bound, lower) {
            (None, true) => Some(lowercase(&text)),
            (None, false) => Some(uppercase(&text)),
            (Some(bound), true) => lowercase_within(&text, bound),
            (Some(bound), false) => uppercase_within(&text, bound),
        };
        if bound.is_none() && past {
            return Some("a conversion without a bound is never past one".into());
        }
        (answer != mapped).then(|| {
            format!(
                "{} is {:?}, not {:?}",
                shown(&text),
                answer.as_deref().map(shown),
                mapped.as_deref().map(shown)
            )
        })
    });
}

#[test]
fn normalization_within_a_bound_answers_every_line_of_the_suite() {
    each_line("suite/normalization-bound.txt", 5, |line| {
        let text = line.text(0);
        let form = match line.one_of(1, &["NFC", "NFD", "NFKC", "NFKD"]) {
            "NFC" => Form::Nfc,
            "NFD" => Form::Nfd,
            "NFKC" => Form::Nfkc,
            _ => Form::Nfkd,
        };
        let bound = line.number(2);
        let past = line.one_of(3, &["NORMALIZED", "PAST"]) == "PAST";
        let normalized = if past {
            line.empty(4);
            None
        } else {
            Some(line.text(4))
        };
        let answer = normalize_within(form, &text, bound);
        (answer != normalized).then(|| {
            format!(
                "{} in {form:?} within {bound} is {:?}",
                shown(&text),
                answer.as_deref().map(shown)
            )
        })
    });
}

#[test]
fn temporal_text_answers_every_line_of_the_suite() {
    each_line("suite/temporal.txt", 3, |line| {
        let kind = match line.one_of(
            0,
            &["DATE", "TIME", "DATETIME", "OFFSET_DATETIME", "INSTANT"],
        ) {
            "DATE" => TemporalKind::Date,
            "TIME" => TemporalKind::Time,
            "DATETIME" => TemporalKind::DateTime,
            "OFFSET_DATETIME" => TemporalKind::OffsetDateTime,
            _ => TemporalKind::Instant,
        };
        let text = line.text(1);
        let admitted = line.one_of(2, &["ADMITTED", "REFUSED"]) == "ADMITTED";
        let answer = check_temporal(kind, &text);
        (answer.is_ok() != admitted).then(|| format!("{} as {kind:?} is {answer:?}", shown(&text)))
    });
}
