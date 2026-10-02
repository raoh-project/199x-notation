//! The images in `image/p1.txt`, and what each accepts, as `image/P1.md` says.

mod common;

use common::{each_line, shown};
use notation199x::{NotAnImage, Pattern};

#[test]
fn images_are_read_as_every_line_of_the_fixtures_says() {
    each_line("image/p1.txt", 3, |line| {
        let image = line.text_as_shown(0);
        let refused = line.is_empty(1) && line.fields_equal(2, "REFUSED");
        let subject = if refused {
            line.empty(1);
            String::new()
        } else {
            line.text(1)
        };
        let outcome = line.one_of(2, &["ACCEPTED", "NOT_ACCEPTED", "REFUSED"]);
        let read = Pattern::from_image(&image);
        match (outcome, &read) {
            ("REFUSED", Err(NotAnImage)) => None,
            ("ACCEPTED", Ok(pattern)) if pattern.matches(&subject) => None,
            ("NOT_ACCEPTED", Ok(pattern)) if !pattern.matches(&subject) => None,
            _ => Some(format!(
                "{} with {}: {}, not {outcome}",
                shown(&image),
                shown(&subject),
                match &read {
                    Err(_) => "refused".into(),
                    Ok(pattern) => format!("accepted: {}", pattern.matches(&subject)),
                }
            )),
        }
    });
}

/// A count is read as a promise the image keeps or breaks, and nothing is made ready for it before
/// what it counts is read: an image that counts the most of everything and holds nothing is refused
/// at once.
#[test]
fn a_count_the_image_does_not_hold_makes_nothing_ready() {
    for image in [
        "P1,0,2147483647",
        "P1,0,0,2147483647,0,2147483647",
        "P1,0,1,2147483647,0,0,1,0,0,0",
        "P1,0,0,1,0,0,2147483647",
    ] {
        assert_eq!(
            Pattern::from_image(image).unwrap_err(),
            NotAnImage,
            "{image}"
        );
    }
    assert_eq!(
        Pattern::from_image("P1,0,0,2147483648").unwrap_err(),
        NotAnImage
    );
}
