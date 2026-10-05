use core::cmp::Ordering;

/// How many scalar values `text` is made of, which is the length every rule here measures text in,
/// and not [`str::len`], which counts bytes.
pub fn scalar_count(text: &str) -> usize {
    text.chars().count()
}

/// Where `a` stands against `b`: the first scalar value where they differ decides, and where one is
/// a prefix of the other the shorter is below.
///
/// The order of UTF-8 bytes is the order of the scalar values they encode, so this is the order
/// [`Ord`] gives `str`. It is not the order of UTF-16 code units, in which a scalar value past
/// U+FFFF stands below U+E000.
pub fn compare(a: &str, b: &str) -> Ordering {
    a.cmp(b)
}
