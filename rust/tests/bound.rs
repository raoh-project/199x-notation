//! A bounded normalization holds no more than its bound and a number the Unicode tables decide,
//! however long the text.
//!
//! The allocator of this test binary is the system's, counting what each thread asks of it, so
//! what one call makes is measured without what other tests make at the same time.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

use notation199x::{Form, normalize, normalize_within};

struct Counting;

thread_local! {
    static MADE: Cell<usize> = const { Cell::new(0) };
}

// SAFETY: every call is passed on to the system allocator as it was made; the count is a
// thread-local cell, which neither allocates nor unwinds.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        MADE.with(|made| made.set(made.get() + layout.size()));
        // SAFETY: the caller's contract for `alloc` is the system allocator's.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: `ptr` was allocated by `alloc` above, which is the system allocator's.
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        MADE.with(|made| made.set(made.get() + new_size));
        // SAFETY: the caller's contract for `realloc` is the system allocator's.
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static ALLOCATOR: Counting = Counting;

/// The bytes the current thread asks the allocator for while `work` runs.
fn made_by(work: impl FnOnce()) -> usize {
    let before = MADE.with(Cell::get);
    work();
    MADE.with(Cell::get) - before
}

/// A starter and a combining run of a million marks, held to a bound of ten, is found past the bound
/// before the run is held: what is made is the room for the bound and the marks that may compose,
/// not for the run.
#[test]
fn a_bounded_normalization_holds_no_more_than_its_bound() {
    let text = format!("a{}", "\u{0301}".repeat(1_000_000));
    assert_eq!(normalize(Form::Nfc, "a\u{0301}"), "\u{00E1}");
    for form in [Form::Nfc, Form::Nfd, Form::Nfkc, Form::Nfkd] {
        let mut within = Some(String::new());
        let made = made_by(|| within = normalize_within(form, &text, 10));
        assert_eq!(within, None, "{form:?} of the run is within 10");
        assert!(made <= 64 << 10, "{form:?} made {made} bytes");
    }
}
