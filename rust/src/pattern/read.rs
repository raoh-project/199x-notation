use alloc::string::ToString;
use alloc::vec::Vec;

use super::symbols::{Shorthands, Symbols};
use super::tree::{Part, Tree};
use super::{PatternBeyond, PatternLimit, PatternRefusal};
use crate::pattern_alphabet_tables::LETTERS_AND_DIGITS;
use crate::tables::within;

/// What stops the reading where the text is no pattern: why, and the bytes of the construct it
/// stopped in.
pub(crate) struct Refusal {
    pub(crate) why: PatternRefusal,
    pub(crate) from: usize,
    pub(crate) to: usize,
}

/// The reader of the pattern language, and the only one: what it hands on is the tree, and nothing
/// after it reads the text again.
pub(crate) struct Reader<'a> {
    text: &'a str,
    at: usize,
    depth: usize,
    /// Where the construct being read begins, which is what a refusal quotes.
    construct: usize,
    /// The first limit met in the text, or `None` while none has been.
    pub(crate) past: Option<PatternBeyond>,
    pub(crate) tree: Tree,
    shorthands: Shorthands,
}

/// A choice being read, in a group or at the top: the arms read so far, and the parts of the one
/// being read.
struct Open {
    arms: Vec<usize>,
    parts: Vec<usize>,
}

impl Open {
    fn new() -> Open {
        Open {
            arms: Vec::new(),
            parts: Vec::new(),
        }
    }

    /// Puts `part` at the end of the arm being read. A group of nothing is nothing, and is left out
    /// so that one written pattern has one tree. An anchor is not one of those: where it stands
    /// decides what it comes to.
    fn part(&mut self, tree: &Tree, part: usize) {
        if !matches!(tree.parts[part], Part::Nothing) {
            self.parts.push(part);
        }
    }

    /// The arm being read, ended: an arm of one part is that part, and an arm of none is nothing.
    fn end_arm(&mut self, tree: &mut Tree) {
        let parts = core::mem::take(&mut self.parts);
        let arm = match parts.len() {
            0 => tree.add(Part::Nothing),
            1 => parts[0],
            _ => tree.add(Part::InTurn(parts)),
        };
        self.arms.push(arm);
    }

    /// The choice, ended: a choice of one arm is that arm.
    fn choice(mut self, tree: &mut Tree) -> usize {
        self.end_arm(tree);
        if self.arms.len() == 1 {
            self.arms[0]
        } else {
            tree.add(Part::EitherOf(self.arms))
        }
    }
}

/// A repetition's count as written: its digits without leading zeros, and the value it is held at,
/// which is one past the limit where it is past that.
#[derive(Clone, Copy)]
struct Count<'a> {
    digits: &'a str,
    held: u32,
}

impl Count<'_> {
    /// Whether this is a smaller number than `other`, compared as written.
    fn below(&self, other: &Count<'_>) -> bool {
        (self.digits.len(), self.digits) < (other.digits.len(), other.digits)
    }
}

impl<'a> Reader<'a> {
    pub(crate) fn new(text: &'a str) -> Reader<'a> {
        Reader {
            text,
            at: 0,
            depth: 0,
            construct: 0,
            past: None,
            tree: Tree::new(),
            shorthands: Shorthands::new(),
        }
    }

    /// The whole text, as what it is written as. A choice is read with a stack of the choices open
    /// around it, so a group is a push and its closing bracket a pop, and nothing here recurses.
    pub(crate) fn pattern(&mut self) -> Result<usize, Refusal> {
        let mut around: Vec<Open> = Vec::new();
        let mut reading = Open::new();
        loop {
            match self.peek() {
                Some(b'(') => {
                    self.construct = self.at;
                    self.opened()?;
                    around.push(core::mem::replace(&mut reading, Open::new()));
                }
                Some(b'|') => {
                    self.at += 1;
                    reading.end_arm(&mut self.tree);
                }
                Some(b')') | None => {
                    let choice = reading.choice(&mut self.tree);
                    let Some(outer) = around.pop() else {
                        if !self.done() {
                            // A bracket closing nothing, which is what is left when the reading of a
                            // choice stops before the end.
                            self.construct = self.at;
                            self.at += 1;
                            return Err(self.refuse(PatternRefusal::SomethingUnclosed));
                        }
                        return Ok(choice);
                    };
                    self.expect(b')')?;
                    self.depth -= 1;
                    reading = outer;
                    self.construct = self.at;
                    let quantified = self.quantified(choice)?;
                    reading.part(&self.tree, quantified);
                }
                Some(_) => {
                    self.construct = self.at;
                    let atom = self.atom()?;
                    let quantified = self.quantified(atom)?;
                    reading.part(&self.tree, quantified);
                }
            }
        }
    }

    /// Reads a group's opening, plain or `(?:`, which are the two the grammar has.
    fn opened(&mut self) -> Result<(), Refusal> {
        self.expect(b'(')?;
        if self.peek() == Some(b'?') {
            self.take()?;
            // (?: and nothing else. A lookaround and a named group have no spelling in the grammar,
            // and a flag group would change what a class means for the rest of the pattern.
            if self.peek() != Some(b':') {
                self.take()?;
                return Err(self.refuse(PatternRefusal::AGroupTheGrammarDoesNotHave));
            }
            self.take()?;
        }
        self.depth += 1;
        if self.depth > PatternLimit::NestingDepth.most() {
            // The group that went past it, from its bracket to where its reading stopped.
            self.beyond(PatternLimit::NestingDepth, self.construct, self.at);
        }
        Ok(())
    }

    /// `one` with the count written after it, if any.
    fn quantified(&mut self, one: usize) -> Result<usize, Refusal> {
        self.construct = self.at;
        let (least, most) = match self.peek() {
            Some(b'?') => (0, Some(1)),
            Some(b'*') => (0, None),
            Some(b'+') => (1, None),
            Some(b'{') => {
                self.take()?;
                let floor = self.count()?;
                let mut ceiling = Some(floor);
                if self.peek() == Some(b',') {
                    self.take()?;
                    ceiling = if self.peek() == Some(b'}') {
                        None
                    } else {
                        Some(self.count()?)
                    };
                }
                if self.peek() != Some(b'}') {
                    return Err(self.unclosed());
                }
                // Compared as written, since either may be past what a count is held at.
                if ceiling
                    .as_ref()
                    .is_some_and(|ceiling| ceiling.below(&floor))
                {
                    self.take()?;
                    return Err(self.refuse(PatternRefusal::ACountThisCannotRead));
                }
                (floor.held, ceiling.map(|ceiling| ceiling.held))
            }
            _ => return Ok(one),
        };
        self.take()?;
        // Reluctant says how a matcher walks and not which strings are accepted, so the marker is
        // read and left out. Possessive is not one of those: it takes what it can and gives none of
        // it back, so which strings it accepts follows from how a matcher walks.
        match self.peek() {
            Some(b'?') => self.at += 1,
            Some(b'+') => {
                self.at += 1;
                return Err(self.refuse(PatternRefusal::APossessiveRepetition));
            }
            _ => {}
        }
        Ok(self.tree.add(Part::Repeated {
            body: one,
            least,
            most,
        }))
    }

    /// One thing written, other than a group.
    fn atom(&mut self) -> Result<usize, Refusal> {
        let set = match self.peek() {
            Some(b'[') => {
                self.take()?;
                self.character_class()?
            }
            Some(b'\\') => {
                self.take()?;
                self.escaped()?
            }
            Some(b'.') => {
                self.take()?;
                // Every symbol but the line terminators, written as a difference, so that a negated
                // class, which does not leave them out, is the same algebra with a different set
                // taken away.
                self.shorthands.dot.clone()
            }
            Some(anchor @ (b'^' | b'$')) => {
                self.take()?;
                return Ok(self.tree.add(Part::Anchor {
                    end: anchor == b'$',
                }));
            }
            Some(b'{') => {
                // A brace that begins no count. Read as an ordinary character it would be a pattern
                // meaning one thing here and a count wherever a digit followed it.
                self.take()?;
                return Err(self.refuse(PatternRefusal::ACountThisCannotRead));
            }
            Some(b'*' | b'+' | b'?') => {
                self.take()?;
                return Err(self.refuse(PatternRefusal::SomethingUnclosed));
            }
            _ => Symbols::one(self.literal()?),
        };
        Ok(self.tree.symbols(set))
    }

    /// What is between `[` and `]`, as the symbols it holds. The `[` is already taken.
    fn character_class(&mut self) -> Result<Symbols, Refusal> {
        let negated = self.peek() == Some(b'^');
        if negated {
            self.take()?;
        }
        // Gathered and put in order once, so that a class does not cost the square of its length.
        let mut members: Vec<(char, char)> = Vec::new();
        let mut first = true;
        while !self.done() && (self.peek() != Some(b']') || first) {
            first = false;
            self.construct = self.at;
            if self.peek() == Some(b'[') {
                self.take()?;
                return Err(self.refuse(PatternRefusal::AClassOfClasses));
            }
            if self.text[self.at..].starts_with("&&") {
                self.at += 2;
                return Err(self.refuse(PatternRefusal::AClassOfClasses));
            }
            members.extend_from_slice(self.class_member()?.runs());
        }
        self.expect(b']')?;
        let held = Symbols::normalized(members);
        if held.is_empty() {
            return Err(self.refuse(PatternRefusal::SomethingUnclosed));
        }
        // The universe less what is written. A negated class does not leave out the line
        // terminators, which is why . is written as a difference of its own.
        Ok(if negated { held.not() } else { held })
    }

    /// One member of a class: a symbol, a run of them, or a shorthand's whole set. A run is read
    /// only where both ends are one symbol: `[\d-z]` names no run.
    fn class_member(&mut self) -> Result<Symbols, Refusal> {
        let member = self.class_atom()?;
        let bytes = self.text.as_bytes();
        if let Some(lower) = member.single()
            && self.peek() == Some(b'-')
            && bytes.get(self.at + 1).is_some_and(|next| *next != b']')
        {
            self.take()?;
            let Some(upper) = self.class_atom()?.single() else {
                return Err(self.refuse(PatternRefusal::AnEscapeThisDoesNotRead));
            };
            if upper < lower {
                return Err(self.refuse(PatternRefusal::ACountThisCannotRead));
            }
            return Ok(Symbols::between(lower, upper));
        }
        Ok(member)
    }

    fn class_atom(&mut self) -> Result<Symbols, Refusal> {
        if self.peek() == Some(b'\\') {
            self.take()?;
            return self.escaped();
        }
        Ok(Symbols::one(self.literal()?))
    }

    /// What an escape stands for, as symbols. The backslash is already taken.
    fn escaped(&mut self) -> Result<Symbols, Refusal> {
        // The whole character after the backslash, so that one past the basic plane is classified as
        // the character it is.
        let Some(kind) = self.text[self.at..].chars().next() else {
            return Err(self.refuse(PatternRefusal::AnEscapeThisDoesNotRead));
        };
        let refusal = match kind {
            // The shorthands, as the language defines them.
            'd' | 'D' | 'w' | 'W' | 's' | 'S' => {
                self.take()?;
                let set = match kind.to_ascii_lowercase() {
                    'd' => &self.shorthands.digit,
                    'w' => &self.shorthands.word,
                    _ => &self.shorthands.space,
                };
                return Ok(if kind.is_ascii_uppercase() {
                    set.not()
                } else {
                    set.clone()
                });
            }
            'n' | 't' | 'r' | 'f' | 'a' | 'e' => {
                self.take()?;
                return Ok(Symbols::one(match kind {
                    'n' => '\n',
                    't' => '\t',
                    'r' => '\r',
                    'f' => '\u{0C}',
                    'a' => '\u{07}',
                    _ => '\u{1B}',
                }));
            }
            '0' => {
                self.take()?;
                return Ok(Symbols::one(self.octal()?));
            }
            'x' | 'u' => {
                self.take()?;
                let spelled = if kind == 'x' {
                    hex_escape(self.text, self.at)
                } else {
                    unicode_escape(self.text, self.at)
                };
                let Some((symbol, end)) = spelled else {
                    return Err(self.refuse(PatternRefusal::AnEscapeThisDoesNotRead));
                };
                self.at = end;
                return Ok(Symbols::one(self.symbol(symbol)?));
            }
            'p' | 'P' => PatternRefusal::ACharacterProperty,
            'b' | 'B' | 'A' | 'z' | 'Z' | 'G' | 'R' => PatternRefusal::ABoundary,
            'Q' | 'E' => PatternRefusal::AQuotation,
            'k' | '1'..='9' => PatternRefusal::ABackReference,
            // An escaped literal: \. \+ \\ \-. A letter or a decimal digit with no meaning is refused
            // rather than read as itself: read as itself, one given a meaning later would change
            // which strings an old pattern accepts.
            _ if within(LETTERS_AND_DIGITS, kind) => PatternRefusal::AnEscapeThisDoesNotRead,
            _ => return Ok(Symbols::one(self.literal()?)),
        };
        // The escape is quoted with the character after the backslash whole.
        self.take()?;
        Err(self.refuse(refusal))
    }

    /// The code point a `\x` or `\u` escape spells as a symbol. A surrogate is no symbol, since no
    /// text holds one, and is refused.
    fn symbol(&self, code_point: u32) -> Result<char, Refusal> {
        char::from_u32(code_point)
            .ok_or_else(|| self.refuse(PatternRefusal::ACharacterNoStringHolds))
    }

    /// `\0n`, `\0nn` or `\0mnn`: up to three octal digits after the zero, up to 377.
    fn octal(&mut self) -> Result<char, Refusal> {
        let mut value = 0u32;
        let mut digits = 0;
        while digits < 3
            && let Some(digit @ b'0'..=b'7') = self.peek()
        {
            value = value * 8 + u32::from(digit - b'0');
            self.at += 1;
            digits += 1;
        }
        if digits == 0 || value > 0xFF {
            return Err(self.refuse(PatternRefusal::AnEscapeThisDoesNotRead));
        }
        Ok(char::from_u32(value).expect("a value up to 377 octal is a scalar value"))
    }

    /// The symbol written here, a whole scalar value.
    fn literal(&mut self) -> Result<char, Refusal> {
        let Some(written) = self.text[self.at..].chars().next() else {
            return Err(self.refuse(PatternRefusal::SomethingUnclosed));
        };
        self.at += written.len_utf8();
        Ok(written)
    }

    /// A repetition's count. Every digit is read, and a count past the limit is noted and held at
    /// one more than it, which is all that is asked of its value; the digits are kept so that a
    /// floor and a ceiling are compared as they are written.
    fn count(&mut self) -> Result<Count<'a>, Refusal> {
        let from = self.at;
        let most = PatternLimit::RepetitionCount.most() as u32;
        let mut value = 0u32;
        while let Some(digit @ b'0'..=b'9') = self.peek() {
            value = (value * 10 + u32::from(digit - b'0')).min(most + 1);
            self.at += 1;
        }
        if self.at == from {
            return Err(self.refuse(PatternRefusal::ACountThisCannotRead));
        }
        if value > most {
            self.beyond(PatternLimit::RepetitionCount, from, self.at);
        }
        let digits = self.text[from..self.at].trim_start_matches('0');
        Ok(Count {
            digits: if digits.is_empty() { "0" } else { digits },
            held: value,
        })
    }

    /// Notes the first limit met in the text, which is the answer if the text turns out to be a
    /// pattern.
    fn beyond(&mut self, limit: PatternLimit, from: usize, to: usize) {
        if self.past.is_none() {
            self.past = Some(PatternBeyond {
                limit,
                from,
                construct: self.text[from..to].to_string(),
            });
        }
    }

    fn done(&self) -> bool {
        self.at >= self.text.len()
    }

    /// The byte here. Read as a byte rather than as a symbol, because what the grammar branches on
    /// is punctuation, all of it ASCII.
    fn peek(&self) -> Option<u8> {
        self.text.as_bytes().get(self.at).copied()
    }

    /// Moves past the character here, whole.
    fn take(&mut self) -> Result<(), Refusal> {
        let Some(here) = self.text[self.at..].chars().next() else {
            return Err(self.refuse(PatternRefusal::SomethingUnclosed));
        };
        self.at += here.len_utf8();
        Ok(())
    }

    fn expect(&mut self, c: u8) -> Result<(), Refusal> {
        if self.peek() != Some(c) {
            return Err(self.unclosed());
        }
        self.take()
    }

    /// A closing that is missing, where it was looked for, which is what an author is sent to.
    fn unclosed(&mut self) -> Refusal {
        self.construct = self.at;
        self.refuse(PatternRefusal::SomethingUnclosed)
    }

    /// The construct being read, refused, quoted from where it began to where the reading stopped.
    fn refuse(&self, why: PatternRefusal) -> Refusal {
        Refusal {
            why,
            from: self.construct,
            to: self.at,
        }
    }
}

/// What `\u` spells, read from `at`, just past the u, and where it ends: four hex digits, and where
/// they are a high surrogate followed by a `\u` escape of a low one, the one character the two
/// encode. `None` where there are not four hex digits. A high escape with no low one after it spells
/// the high surrogate, which no text holds and the reader refuses.
fn unicode_escape(text: &str, at: usize) -> Option<(u32, usize)> {
    let first = fixed_hex(text, at, 4)?;
    let next = at + 4;
    if (0xD800..=0xDBFF).contains(&first)
        && text[next..].starts_with("\\u")
        && let Some(second) =
            fixed_hex(text, next + 2, 4).filter(|second| (0xDC00..=0xDFFF).contains(second))
    {
        return Some((
            0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00),
            next + 6,
        ));
    }
    Some((first, next))
}

/// What `\x` spells, read from `at`, just past the x, and where it ends: two hex digits, or any
/// number of them in braces up to U+10FFFF.
fn hex_escape(text: &str, at: usize) -> Option<(u32, usize)> {
    if !text[at..].starts_with('{') {
        return Some((fixed_hex(text, at, 2)?, at + 2));
    }
    let bytes = text.as_bytes();
    let mut value = 0u32;
    let mut here = at + 1;
    while here < bytes.len() && bytes[here] != b'}' {
        value = value * 16 + hex_digit(bytes[here])?;
        if value > u32::from(char::MAX) {
            return None;
        }
        here += 1;
    }
    (here < bytes.len() && here > at + 1).then_some((value, here + 1))
}

/// The `digits` hex digits at `at` as a number.
fn fixed_hex(text: &str, at: usize, digits: usize) -> Option<u32> {
    let written = text.as_bytes().get(at..at + digits)?;
    written
        .iter()
        .try_fold(0, |value, digit| Some(value * 16 + hex_digit(*digit)?))
}

/// The value of an ASCII hex digit, in either case. A fullwidth digit is no digit to the language.
fn hex_digit(c: u8) -> Option<u32> {
    char::from(c).to_digit(16)
}
