use crate::tables::within;
use crate::white_space_tables::WHITE_SPACE;

/// Whether `c` is in Unicode 18.0.0's `White_Space` set.
///
/// Not [`char::is_whitespace`], which answers by the Unicode version of the Rust release.
pub fn is_white_space(c: char) -> bool {
    within(WHITE_SPACE, c)
}
