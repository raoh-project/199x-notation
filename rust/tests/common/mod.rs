//! Reading the vectors in the repository's `suite` and `image` directories, as `suite/README.md`
//! states their format.
//!
//! The directories are outside this crate: a package made from `rust/` holds what is under it, so
//! a copy of the crate that Cargo fetched has neither. A test that reads them runs in a checkout,
//! skips where they are not there, and fails instead where `NOTATION199X_REQUIRE_SUITE` is set, as
//! CI sets it, so that a checkout missing them is not taken for a crate without them.

#![allow(dead_code)]

use std::fmt::Write as _;
use std::path::PathBuf;

const REQUIRE_SUITE: &str = "NOTATION199X_REQUIRE_SUITE";

/// The path of `name` under the repository's root, or `None` where the file is not there and the
/// environment does not require it.
pub fn repository_file(name: &str) -> Option<PathBuf> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join(name);
    if path.exists() {
        return Some(path);
    }
    if std::env::var_os(REQUIRE_SUITE).is_some() {
        panic!(
            "{} is missing, and {REQUIRE_SUITE} is set: the tests run in rust/ of a checkout",
            path.display()
        );
    }
    eprintln!(
        "skipped: {} is outside this crate, and is read only in a checkout of the repository",
        path.display()
    );
    None
}

/// One line of vectors, and which of its fields have been read.
pub struct Line {
    where_: String,
    fields: Vec<String>,
    read: Vec<bool>,
    wrong: Option<String>,
}

impl Line {
    /// Field `i` as written, marked read.
    pub fn take(&mut self, i: usize) -> String {
        self.read[i] = true;
        self.fields[i].clone()
    }

    /// Whether field `i` is written as `value`, without reading it.
    pub fn fields_equal(&self, i: usize, value: &str) -> bool {
        self.fields[i] == value
    }

    /// Whether field `i` is empty, without reading it.
    pub fn is_empty(&self, i: usize) -> bool {
        self.fields[i].is_empty()
    }

    fn wrong(&mut self, i: usize, what: &str) {
        if self.wrong.is_none() {
            self.wrong = Some(format!(
                "{}: field {} is {:?}, not {what}",
                self.where_,
                i + 1,
                self.fields[i]
            ));
        }
    }

    /// Reads a field a line leaves empty where it asserts nothing there.
    pub fn empty(&mut self, i: usize) {
        if !self.take(i).is_empty() {
            self.wrong(i, "empty");
        }
    }

    /// Reads a field as the text it writes: scalar values in hex, separated by spaces.
    pub fn text(&mut self, i: usize) -> String {
        let field = self.take(i);
        let mut text = String::new();
        for each in field.split_whitespace() {
            let hex = (4..=6).contains(&each.len())
                && each
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'A'..=b'F').contains(&b));
            match u32::from_str_radix(each, 16)
                .ok()
                .filter(|_| hex)
                .and_then(char::from_u32)
            {
                Some(c) => text.push(c),
                None => {
                    self.wrong(i, "a text of scalar values in hex");
                    return String::new();
                }
            }
        }
        text
    }

    /// Reads a field as the text it shows: printable ASCII other than a space as itself, and any
    /// other scalar value as `<U+XXXX>`, as `image/p1.txt` writes an image.
    pub fn text_as_shown(&mut self, i: usize) -> String {
        let field = self.take(i);
        let mut text = String::new();
        let mut rest = field.as_str();
        while let Some(c) = rest.chars().next() {
            if c == '<' {
                let named = rest.find('>').map(|end| (&rest[1..end], end));
                let scalar = named.and_then(|(named, _)| {
                    let hex = named.strip_prefix("U+")?;
                    let well_written = (4..=6).contains(&hex.len())
                        && hex
                            .bytes()
                            .all(|b| b.is_ascii_digit() || (b'A'..=b'F').contains(&b));
                    well_written
                        .then(|| u32::from_str_radix(hex, 16).ok())
                        .flatten()
                        .and_then(char::from_u32)
                });
                let (Some(scalar), Some((_, end))) = (scalar, named) else {
                    self.wrong(i, "printable ASCII and <U+XXXX> of a scalar value");
                    return String::new();
                };
                text.push(scalar);
                rest = &rest[end + 1..];
            } else if ('\u{21}'..'\u{7F}').contains(&c) {
                text.push(c);
                rest = &rest[1..];
            } else {
                self.wrong(i, "printable ASCII other than a space, and <U+XXXX>");
                return String::new();
            }
        }
        text
    }

    /// Reads a field as the one scalar value it writes.
    pub fn scalar(&mut self, i: usize) -> char {
        let text = self.text(i);
        let mut chars = text.chars();
        match (chars.next(), chars.next()) {
            (Some(c), None) => c,
            _ => {
                self.wrong(i, "one scalar value");
                '\0'
            }
        }
    }

    /// Reads a field as an unsigned decimal.
    pub fn number(&mut self, i: usize) -> usize {
        let field = self.take(i);
        let canonical =
            field == "0" || (!field.starts_with('0') && !field.is_empty() && field.len() <= 18);
        match field
            .parse::<usize>()
            .ok()
            .filter(|_| canonical && field.bytes().all(|b| b.is_ascii_digit()))
        {
            Some(n) => n,
            None => {
                self.wrong(i, "an unsigned decimal");
                0
            }
        }
    }

    /// Reads a field as an unsigned decimal, or `None` where it is empty.
    pub fn number_or_nothing(&mut self, i: usize) -> Option<usize> {
        if self.is_empty(i) {
            self.empty(i);
            None
        } else {
            Some(self.number(i))
        }
    }

    /// Reads a field as one of `names`.
    pub fn one_of(&mut self, i: usize, names: &[&'static str]) -> &'static str {
        let field = self.take(i);
        match names.iter().find(|name| **name == field) {
            Some(name) => name,
            None => {
                self.wrong(i, &format!("one of {names:?}"));
                ""
            }
        }
    }

    /// Reads a field as one of `names`, or `None` where it is empty.
    pub fn one_of_or_nothing(&mut self, i: usize, names: &[&'static str]) -> Option<&'static str> {
        if self.is_empty(i) {
            self.empty(i);
            None
        } else {
            Some(self.one_of(i, names))
        }
    }

    /// Reads a field as `true` or `false`.
    pub fn yes_or_no(&mut self, i: usize) -> bool {
        match self.take(i).as_str() {
            "true" => true,
            "false" => false,
            _ => {
                self.wrong(i, "true or false");
                false
            }
        }
    }
}

/// Holds the implementation to every line of `name`, a path under the repository's root:
/// `check` reads every field of a line and answers what is wrong on it, or `None`. A line is read
/// whole or not at all, and a field no check reads fails the test, so nothing written in a file goes
/// unchecked. Every line is checked, and the test fails at the end with every line that answered
/// otherwise.
pub fn each_line(name: &str, fields: usize, mut check: impl FnMut(&mut Line) -> Option<String>) {
    let Some(path) = repository_file(name) else {
        return;
    };
    let data = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let lines: Vec<&str> = data
        .strip_suffix('\n')
        .unwrap_or(&data)
        .split('\n')
        .collect();
    let mut sourced = false;
    let mut vectors = 0;
    let mut failures = Vec::new();
    for (n, text) in lines.iter().enumerate() {
        if *text == "# Source:"
            && lines
                .get(n + 1)
                .is_some_and(|next| next.starts_with("#   "))
        {
            sourced = true;
        }
        if text.is_empty() || text.starts_with('#') {
            continue;
        }
        let where_ = format!("{name}:{}", n + 1);
        assert!(sourced, "{name} names no source before its first vector");
        let split: Vec<String> = text
            .split(';')
            .map(|f| f.trim_matches(' ').to_string())
            .collect();
        assert_eq!(
            split.len(),
            fields,
            "{where_} has {} fields, not {fields}",
            split.len()
        );
        let mut line = Line {
            where_: where_.clone(),
            fields: split,
            read: vec![false; fields],
            wrong: None,
        };
        let said = check(&mut line);
        if let Some(i) = line.read.iter().position(|read| !read) {
            panic!("{where_}: field {} is not read", i + 1);
        }
        if let Some(wrong) = line.wrong {
            panic!("{wrong}");
        }
        if let Some(said) = said {
            failures.push(format!("{where_}: {said}"));
        }
        vectors += 1;
    }
    assert!(vectors > 0, "{name} holds no vectors");
    assert!(
        failures.is_empty(),
        "{} lines answered otherwise:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// `text` as a message shows it: printable ASCII as itself, and every other scalar value as
/// `<U+XXXX>`.
pub fn shown(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        if (' '..'\u{7F}').contains(&c) {
            out.push(c);
        } else {
            let _ = write!(out, "<U+{:04X}>", u32::from(c));
        }
    }
    out.push('"');
    out
}
