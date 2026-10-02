use alloc::vec::Vec;

/// A deterministic machine as an image of P2 writes it: the scalar values cut into pieces, each in
/// a class, and each state's row as spans of classes, each leading to one state. As large as the
/// image and no larger, however many states and classes there are, and walked as it is: a scalar
/// value is a search of the pieces for its class and of the state's spans for where that leads.
///
/// Every scalar value is in one piece and every class in one span of each state, which the reader
/// held the image to, so a walk is one state at a time and every character leads somewhere.
pub(crate) struct Spans {
    /// Where each piece ends, ascending, the last at U+10FFFF, and the class of each.
    pub(crate) lasts: Vec<u32>,
    pub(crate) classes: Vec<u32>,
    pub(crate) accepting: Vec<bool>,
    /// The spans of state `q` are `ends[starts[q]..starts[q + 1]]`, each the last class it covers,
    /// ascending, and `to` at the same places, the state each leads to.
    pub(crate) starts: Vec<usize>,
    pub(crate) ends: Vec<u32>,
    pub(crate) to: Vec<u32>,
}

impl Spans {
    /// Whether the whole of `subject` is accepted: the one walk from state 0, a scalar value at a
    /// time, each two searches.
    pub(crate) fn matches(&self, subject: &str) -> bool {
        let mut q = 0;
        for c in subject.chars() {
            let class = self.classes[self.lasts.partition_point(|&last| last < u32::from(c))];
            let (from, to) = (self.starts[q], self.starts[q + 1]);
            let span = self.ends[from..to].partition_point(|&end| end < class);
            q = self.to[from + span] as usize;
        }
        self.accepting[q]
    }
}
