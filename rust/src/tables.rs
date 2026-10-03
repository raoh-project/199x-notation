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

/// Reading the repository's copy of the database, for the tests that hold the tables to it by a
/// reading of their own rather than take them on the generator's word.
#[cfg(test)]
pub(crate) mod ucd {
    extern crate std;

    use alloc::string::String;
    use alloc::vec;
    use alloc::vec::Vec;

    /// `name` under the repository's `ucd/18.0.0`, or `None` where it is not there and the
    /// environment does not require it, as the tests under `tests/` read the repository's files.
    pub(crate) fn ucd(name: &str) -> Option<String> {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../ucd/18.0.0")
            .join(name);
        match std::fs::read_to_string(&path) {
            Ok(text) => Some(text),
            Err(_) if std::env::var_os("NOTATION199X_REQUIRE_SUITE").is_none() => None,
            Err(error) => panic!("{}: {error}", path.display()),
        }
    }

    /// The first and last code point of a field of a UCD line, `XXXX` or `XXXX..YYYY`.
    pub(crate) fn range(field: &str) -> (u32, u32) {
        let field = field.trim();
        match field.split_once("..") {
            Some((first, last)) => (
                u32::from_str_radix(first, 16).unwrap(),
                u32::from_str_radix(last, 16).unwrap(),
            ),
            None => {
                let only = u32::from_str_radix(field, 16).unwrap();
                (only, only)
            }
        }
    }

    /// For each code point, whether a property file's `text` gives it `name`.
    pub(crate) fn property(text: &str, name: &str) -> Vec<bool> {
        let mut has = vec![false; 0x110000];
        for line in text.lines() {
            let fields: Vec<&str> = line.split('#').next().unwrap().split(';').collect();
            if fields.len() < 2 || fields[1].trim() != name {
                continue;
            }
            let (first, last) = range(fields[0]);
            for cp in first..=last {
                has[cp as usize] = true;
            }
        }
        assert!(has.contains(&true), "no code point is {name}");
        has
    }
}
