//! How long a match takes on each path a walk of a pattern's steps goes by, measured apart.
//!
//! ```sh
//! cargo run --release --example walk_paths
//! ```
//!
//! A walk keeps no sets, keeps a new set at most characters, or looks up sets it has kept. Work put
//! in the step every walk takes for the sake of one of these is paid by the others, so each is
//! measured on its own, and a change is compared on each: one time over all three would let what
//! one path loses be hidden by what another gains.

use notation199x::{PatternRead, read_pattern};
use std::time::{Duration, Instant};

fn time(mut f: impl FnMut() -> bool) -> Duration {
    let (start, mut times) = (Instant::now(), 0);
    while start.elapsed() < Duration::from_millis(1_000) || times < 3 {
        std::hint::black_box(f());
        times += 1;
    }
    start.elapsed() / times
}

fn pattern(text: &str) -> notation199x::Pattern {
    match read_pattern(text) {
        PatternRead::Pattern(pattern) => pattern,
        other => panic!("{text}: {other:?}"),
    }
}

fn main() {
    // Each set of states is as large as the machine, so the first match gives keeping sets up, and
    // the matcher walks every match after it a state at a time.
    let large = pattern("(?:a?){49998}");
    let a = "a".repeat(100);
    let mut alone = large.matcher();
    alone.matches(&a);
    let without = time(|| alone.matches(&a));

    // The tenth character from the end is an a: a match that has kept nothing comes to a new set
    // at most characters of a subject at random.
    let tenth = pattern("(?:a|b)*a(?:a|b){8}");
    let mut seed = 9u32;
    let random: String = (0..400)
        .map(|_| {
            seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            if seed >> 31 == 0 { 'a' } else { 'b' }
        })
        .collect();
    let new = time(|| tenth.matches(&random));

    // The same subject again with one matcher: every set it comes to is kept, and every step is
    // one lookup.
    let mut kept = tenth.matcher();
    kept.matches(&random);
    let again = time(|| kept.matches(&random));

    println!(
        "{:<52} {:>10.2?}",
        "without kept sets, (?:a?){49998} against 100 a", without
    );
    println!(
        "{:<52} {:>10.2?}",
        "a new set kept at most characters, 400 bytes", new
    );
    println!(
        "{:<52} {:>10.2?}",
        "kept sets looked up again, 400 bytes", again
    );
}
