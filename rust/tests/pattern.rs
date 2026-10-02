//! What the suite cannot write: answers about where a refusal points, and text too large for a
//! vector.

use notation199x::*;

/// Text nested as deeply as it is long is read to its end, its anchors placed, and dropped, with no
/// stack of its own: the depth is a limit on a pattern, asked once the text is known to be one.
#[test]
fn text_nested_as_deeply_as_it_is_long_is_read_to_its_end() {
    let deep = 1_000_000;
    let nested = format!("{}^a{}", "(".repeat(deep), ")".repeat(deep));
    match read_pattern(&nested) {
        PatternRead::Beyond(beyond) => {
            assert_eq!(beyond.limit, PatternLimit::NestingDepth);
            assert_eq!(beyond.from, 200);
        }
        other => panic!("{other:?}"),
    }
    let unclosed = format!("{}a{}", "(".repeat(deep), ")".repeat(deep - 1));
    assert!(matches!(
        read_pattern(&unclosed),
        PatternRead::Refused(PatternRefused {
            why: PatternRefusal::SomethingUnclosed,
            ..
        })
    ));
    let unplaceable = format!("{}(a|)^b{}", "(".repeat(deep), ")".repeat(deep));
    assert!(matches!(
        read_pattern(&unplaceable),
        PatternRead::Refused(PatternRefused {
            why: PatternRefusal::AnAnchorThisCannotPlace,
            ..
        })
    ));
}

#[test]
fn a_refusal_quotes_the_construct_it_stopped_in() {
    let refused = |text: &str| match read_pattern(text) {
        PatternRead::Refused(refused) => (refused.why, refused.from, refused.construct),
        other => panic!("{text}: {other:?}"),
    };
    assert_eq!(
        refused("ab\\p{L}"),
        (PatternRefusal::ACharacterProperty, 2, "\\p".into())
    );
    assert_eq!(
        refused("a{3,2}"),
        (PatternRefusal::ACountThisCannotRead, 1, "{3,2}".into())
    );
    assert_eq!(
        refused("(?<n>a)"),
        (PatternRefusal::AGroupTheGrammarDoesNotHave, 0, "(?<".into())
    );
    assert_eq!(
        refused("[a"),
        (PatternRefusal::SomethingUnclosed, 2, "".into())
    );
    assert_eq!(
        refused("\\uD800"),
        (PatternRefusal::ACharacterNoStringHolds, 0, "\\uD800".into())
    );
    assert_eq!(
        refused("a++"),
        (PatternRefusal::APossessiveRepetition, 1, "++".into())
    );
    assert_eq!(
        refused("\\😀\\q"),
        (PatternRefusal::AnEscapeThisDoesNotRead, 5, "\\q".into())
    );
}
