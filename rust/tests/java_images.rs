//! The images Java writes of the suite's patterns, read here and held to what the suite says each
//! pattern accepts.
//!
//! Java's tests write them to `java/target/images/pattern-match.txt`, and CI hands that file to
//! these tests by `NOTATION199X_JAVA_IMAGES`. The file is written by every run of Java's tests and
//! never checked in: what is held is the suite, not the images' text and not Java's matcher. Where
//! the variable is not set, as in a run of this crate alone, there is nothing to read and the test
//! says so.

mod common;

use std::collections::HashMap;

use common::{each_line, shown};
use notation199x::Pattern;

const JAVA_IMAGES: &str = "NOTATION199X_JAVA_IMAGES";

fn decode(field: &str) -> String {
    field
        .split_whitespace()
        .map(|hex| char::from_u32(u32::from_str_radix(hex, 16).unwrap()).unwrap())
        .collect()
}

#[test]
fn images_java_writes_accept_what_every_line_of_the_suite_says() {
    let Some(path) = std::env::var_os(JAVA_IMAGES) else {
        eprintln!("skipped: {JAVA_IMAGES} names no file of the images Java writes");
        return;
    };
    let data = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{JAVA_IMAGES}: {e}"));
    let images: HashMap<String, String> = data
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            let (pattern, image) = line
                .split_once(" ; ")
                .unwrap_or_else(|| panic!("{line:?} is no line of images"));
            (decode(pattern), image.to_string())
        })
        .collect();
    each_line("suite/pattern-match.txt", 3, |line| {
        let pattern = line.text(0);
        let subject = line.text(1);
        let accepted = line.yes_or_no(2);
        let Some(image) = images.get(&pattern) else {
            return Some(format!("Java wrote no image of {}", shown(&pattern)));
        };
        match Pattern::from_image(image) {
            Err(refused) => Some(format!(
                "the image Java wrote of {} is {refused}",
                shown(&pattern)
            )),
            Ok(read) => (read.matches(&subject) != accepted).then(|| {
                format!(
                    "the image Java wrote of {} accepts {}: {}",
                    shown(&pattern),
                    shown(&subject),
                    !accepted
                )
            }),
        }
    });
}
