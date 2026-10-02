//! How the generated tables are looked up.

/// Whether `c` is in one of `ranges`, sorted and apart: the first range that ends at or after `c`
/// holds it, or none does.
pub(crate) fn within(ranges: &[(char, char)], c: char) -> bool {
    let at = ranges.partition_point(|&(_, last)| last < c);
    at < ranges.len() && ranges[at].0 <= c
}
