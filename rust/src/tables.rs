//! How the generated tables are looked up.

/// What `c` maps to in `mapping`, sorted by the code point it maps from, or `None` where `c` is not
/// in it.
pub(crate) fn mapped(
    mapping: &'static [(char, &'static [char])],
    c: char,
) -> Option<&'static [char]> {
    mapping
        .binary_search_by_key(&c, |&(from, _)| from)
        .ok()
        .map(|at| mapping[at].1)
}

/// Whether `c` is in one of `ranges`, sorted and apart: the first range that ends at or after `c`
/// holds it, or none does.
pub(crate) fn within(ranges: &[(char, char)], c: char) -> bool {
    let at = ranges.partition_point(|&(_, last)| last < c);
    at < ranges.len() && ranges[at].0 <= c
}
