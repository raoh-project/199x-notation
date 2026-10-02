//! The images in `image/p1.txt` and `image/p2.txt`, and what each accepts, as `image/P1.md` and
//! `image/P2.md` say.

mod common;

use common::{each_line, shown};
use notation199x::{NotAnImage, Pattern};

#[test]
fn images_of_p1_are_read_as_every_line_of_the_fixtures_says() {
    every_line_of("image/p1.txt");
}

#[test]
fn images_of_p2_are_read_as_every_line_of_the_fixtures_says() {
    every_line_of("image/p2.txt");
}

fn every_line_of(fixtures: &str) {
    each_line(fixtures, 3, |line| {
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

/// An image of P2 is read in one pass, however many states step over the same wide classes: the
/// machine of issue #27, two classes of a hundred thousand runs each that every state steps over to
/// states of its own, which an image of P1 that says it is deterministic makes a reader hold
/// against each other at every state.
#[test]
fn an_image_of_p2_is_read_in_one_pass_however_many_states_step_over_wide_classes() {
    let runs = 100_000;
    let states = 100_000;
    let dead = states - 1;
    let mut image = String::from("P2");
    for i in 0..runs {
        let at = 0x10000 + 4 * i;
        image += &format!(",{},0,{at},1,{},0,{},2", at - 1, at + 1, at + 2);
    }
    image += &format!(",1114111,0,{states}");
    for state in 0..dead {
        image += &format!(
            ",1,0,{dead},1,{},2,{}",
            (state + 1) % dead,
            (state + 2) % dead
        );
    }
    image += &format!(",0,2,{dead}");
    let started = std::time::Instant::now();
    let pattern = Pattern::from_image(&image).unwrap();
    assert!(started.elapsed() < std::time::Duration::from_secs(10));
    assert!(pattern.matches("\u{10000}\u{10006}"));
    assert!(!pattern.matches("\u{10001}"));
    assert!(pattern.matches(""));
}
