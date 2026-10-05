/// Reads `subject` from byte `*at`, a class at a time, moving `*state` by `next_ascii` over an ASCII
/// character and by `next` over any other, until the end, or until a step leads to a state `stops`
/// says a walk goes no further from. There the state before it is kept, `*at` is left at the
/// character that led out of it, and the answer is that character's class; at the end it is `None`.
/// The two steps answer the same for the class of an ASCII character; a walk whose step past ASCII
/// asks more than one over ASCII does gives that one as `next_ascii`, so no ASCII character pays
/// for the question.
///
/// This is how every walk reads a subject, whatever it walks: a machine held as its steps
/// ([`super::walk`]) or as its classes and rows ([`super::class_rows`]). An ASCII character is its
/// byte, looked up in `ascii`, four at a time where four are ASCII and then one at a time, each
/// still taken before the next is looked at; any other is decoded from what is left of the subject,
/// so it is never sliced out again, and its class is `class_of`'s, which is asked only of
/// characters past ASCII. The state is handed through by value, so that a walk holds it where a
/// step needs it rather than where it was last written.
// Each argument is one thing a walk tells the reader, and the walks are two: bundling any of them
// would be a type for this one call.
#[allow(clippy::too_many_arguments)]
pub(crate) fn read_classes(
    subject: &str,
    at: &mut usize,
    ascii: &[usize; 128],
    class_of: impl Fn(char) -> usize,
    state: &mut u32,
    next_ascii: impl Fn(u32, usize) -> u32,
    next: impl Fn(u32, usize) -> u32,
    stops: impl Fn(u32) -> bool,
) -> Option<usize> {
    let mut here = *state;
    let mut rest = &subject[*at..];
    let stopped = 'read: loop {
        let bytes = rest.as_bytes();
        if bytes.first().is_some_and(u8::is_ascii) {
            // A run of ASCII is read by where it is in the bytes, four at a time and then one, and
            // what is left is cut once the run ends.
            let mut read = 0;
            for four in bytes.as_chunks::<4>().0 {
                if u32::from_ne_bytes(*four) & 0x8080_8080 != 0 {
                    break;
                }
                for (i, &byte) in four.iter().enumerate() {
                    let class = ascii[usize::from(byte)];
                    let there = next_ascii(here, class);
                    if stops(there) {
                        rest = &rest[read + i..];
                        break 'read Some(class);
                    }
                    here = there;
                }
                read += 4;
            }
            while let Some(&byte) = bytes.get(read)
                && byte.is_ascii()
            {
                let class = ascii[usize::from(byte)];
                let there = next_ascii(here, class);
                if stops(there) {
                    rest = &rest[read..];
                    break 'read Some(class);
                }
                here = there;
                read += 1;
            }
            rest = &rest[read..];
            continue;
        }
        let mut chars = rest.chars();
        let Some(c) = chars.next() else {
            break None;
        };
        let class = class_of(c);
        let there = next(here, class);
        if stops(there) {
            break Some(class);
        }
        here = there;
        rest = chars.as_str();
    };
    *state = here;
    *at = subject.len() - rest.len();
    stopped
}
