//! How long a match takes here and in the `regex` crate, on the same patterns and subjects.
//!
//! ```sh
//! cargo run --release --example against_regex
//! ```
//!
//! A match is of the whole subject, so `regex` is given the pattern between `^(?:` and `)$`. Each
//! pattern here is one both read the same way. `matches` is `Pattern::matches`, which keeps nothing
//! between matches; `Matcher` keeps the sets of states its matches come to, as `regex` keeps its
//! cache.

use notation199x::{PatternRead, read_pattern};
use std::time::{Duration, Instant};

fn time(mut f: impl FnMut() -> bool) -> (Duration, bool) {
    let (start, mut times, mut answer) = (Instant::now(), 0, false);
    while start.elapsed() < Duration::from_millis(300) || times < 3 {
        answer = f();
        times += 1;
    }
    (start.elapsed() / times, answer)
}

fn main() {
    let words = "the quick brown fox jumps over the lazy dog ".repeat(25_000);
    let cases = [
        (
            "email-like, short",
            "[a-z.]+@[a-z]+(?:\\.[a-z]+)*\\.[a-z]{2,6}",
            "someone.long.name@example.subdomain.org".to_string(),
        ),
        ("words, 1.1 MB", "(?:[a-z]+ )*", words.clone()),
        ("contains, 1.1 MB", ".*lazy dog .*", words),
        (
            "alternation, 900 KB",
            "(?:foo|bar|baz)+",
            "foobarbaz".repeat(100_000),
        ),
        (
            "class then 12, 100 KB",
            "[^b]*b.{12}",
            "a".repeat(100_000) + "b" + &"c".repeat(12),
        ),
        ("a{1000}", "a{1000}", "a".repeat(1000)),
        (
            "(?:a|ab)*c, 100 KB",
            "(?:a|ab)*c",
            "ab".repeat(50_000) + "c",
        ),
        ("x{0,5000}y, 5 KB", "x{0,5000}y", "x".repeat(5000) + "y"),
    ];
    println!(
        "{:<24} {:>12} {:>12} {:>12} {:>9}",
        "", "matches", "Matcher", "regex", "/ regex"
    );
    for (name, pattern, subject) in cases {
        let PatternRead::Pattern(ours) = read_pattern(pattern) else {
            panic!("{pattern} is not read")
        };
        let theirs = regex::Regex::new(&format!("^(?:{pattern})$")).expect("regex reads it");
        let (once, answer) = time(|| ours.matches(&subject));
        let mut matcher = ours.matcher();
        let (kept, kept_answer) = time(|| matcher.matches(&subject));
        let (regex, regex_answer) = time(|| theirs.is_match(&subject));
        assert!(
            answer == regex_answer && kept_answer == regex_answer,
            "{name}: the answers differ"
        );
        let ratio = kept.as_secs_f64() / regex.as_secs_f64();
        println!("{name:<24} {once:>12.2?} {kept:>12.2?} {regex:>12.2?} {ratio:>8.1}x");
    }
}
