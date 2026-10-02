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
