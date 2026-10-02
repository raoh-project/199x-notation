//! The rules for reading text that Raoh and Souther share: Unicode 18.0.0 default case conversion
//! and normalization, the `White_Space` set, order and length in Unicode scalar values, the lexical
//! grammar of temporal text, and the pattern language.
//!
//! Each rule answers the same way whatever Rust release it is built with. No rule asks the standard
//! library's Unicode functions, such as [`char::to_lowercase`], or a crate of Unicode data: their
//! answers follow the Unicode version of the release or the crate. The tables are generated from the
//! Unicode Character Database by `gen/Generate.java` in the repository, and the rules are held to the
//! vectors in its `suite` directory, the same vectors every other implementation is held to.
//!
//! Every rule is stated of text that is a sequence of Unicode scalar values, which is what a `str`
//! is, so there is no question to ask of text before it is taken in.
//!
//! The crate is `no_std` and allocates through `alloc`, so that a run time without the standard
//! library can use it. Such a run time provides the global allocator.

#![no_std]
#![forbid(unsafe_code)]
#![warn(missing_docs)]

extern crate alloc;

// The tables gen/Generate.java writes, which rustfmt leaves as they are written.
#[rustfmt::skip]
mod case_tables;
#[rustfmt::skip]
mod normalization_tables;
#[rustfmt::skip]
#[allow(dead_code)]
mod pattern_alphabet_tables;
#[rustfmt::skip]
mod white_space_tables;

mod case;
mod normalization;
mod tables;
mod temporal;
mod text;
mod white_space;

pub use case::{lowercase, lowercase_within, uppercase, uppercase_within};
pub use normalization::{Form, normalize, normalize_within};
pub use temporal::{
    INSTANT_MAX, INSTANT_MIN, TemporalKind, TemporalRefusal, YEAR_MAX, YEAR_MIN, check_temporal,
};
pub use text::{compare, scalar_count};
pub use white_space::is_white_space;
