//! What a matcher keeps of the sets it has worked out is held to the room it is given, counted as
//! the allocator counts it: the room a list holds, and not what of it is in use.
//!
//! The allocator of this test binary is the system's, counting what each thread holds of it, so
//! what one matcher holds is measured without what other tests make at the same time.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

use notation199x::{PatternRead, read_pattern};

struct Counting;

thread_local! {
    static HELD: Cell<isize> = const { Cell::new(0) };
}

// SAFETY: every call is passed on to the system allocator as it was made; the count is a
// thread-local cell, which neither allocates nor unwinds.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        HELD.with(|held| held.set(held.get() + layout.size() as isize));
        // SAFETY: the caller's contract for `alloc` is the system allocator's.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        HELD.with(|held| held.set(held.get() - layout.size() as isize));
        // SAFETY: `ptr` was allocated by `alloc` above, which is the system allocator's.
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        HELD.with(|held| held.set(held.get() + new_size as isize - layout.size() as isize));
        // SAFETY: the caller's contract for `realloc` is the system allocator's.
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static ALLOCATOR: Counting = Counting;

/// The room the kept sets of one cache are held to, as the crate's `KNOWN_BYTES`.
const ROOM: isize = 2 << 20;

/// What the walk a matcher works in holds besides what it keeps: four lists as long as the
/// machine's states, a few dozen here, and the room a list rounds up to.
const WALK: isize = 64 << 10;

/// `n` characters, each `a` or `b`, chosen by a small generator so that the text is the same on
/// every run.
fn either(a: char, b: char, n: usize, seed: &mut u64) -> String {
    (0..n)
        .map(|_| {
            *seed = seed
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            if (*seed >> 33).is_multiple_of(2) {
                a
            } else {
                b
            }
        })
        .collect()
}

/// A matcher of a pattern with more sets than the room holds, after subjects that fill the room,
/// forget it and fill it again, holds no more than the room and what its walk works in, and at
/// least half the room, so that it was filled. One pattern keeps the steps between its sets in
/// their rows, which ASCII is in; the other keeps them past the rows, where a step takes a slot of
/// a table grown by doubling.
#[test]
fn a_matcher_holds_no_more_than_the_room_it_is_given() {
    for (text, a, b) in [
        ("(?:a|b)*a(?:a|b){16}", 'a', 'b'),
        ("(?:α|β)*α(?:α|β){16}", 'α', 'β'),
    ] {
        let PatternRead::Pattern(pattern) = read_pattern(text) else {
            panic!("{text} is a pattern");
        };
        let mut seed = 7;
        let subjects = [
            either(a, b, 200_000, &mut seed),
            either(a, b, 20_000, &mut seed),
            either(a, b, 300, &mut seed),
        ];
        let before = HELD.with(Cell::get);
        let mut matcher = pattern.matcher();
        let mut most = 0;
        for subject in &subjects {
            matcher.matches(subject);
            let held = HELD.with(Cell::get) - before;
            most = most.max(held);
            assert!(held <= ROOM + WALK, "{text}: {held} bytes held");
        }
        assert!(
            most >= ROOM / 2,
            "{text}: the room was not filled, {most} bytes at most"
        );
        drop(matcher);
    }
}
