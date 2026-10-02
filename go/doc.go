// Package notation199x holds the rules for reading text that Raoh and Souther share: Unicode
// 18.0.0 default case conversion and normalization, the White_Space set, order and length in
// Unicode scalar values, the lexical grammar of temporal text, and the pattern language.
//
// Each rule answers the same way whatever Go release it is built with. No rule asks the unicode
// package, golang.org/x/text or the time package: their answers follow the Unicode version and
// the parsers of the release they come with. The tables are generated from the Unicode
// Character Database by gen/Generate.java in the repository, and the rules are held to the
// vectors in its suite directory, the same vectors every other implementation is held to.
//
// Every rule is stated of text that is a sequence of Unicode scalar values. A Go string can hold
// bytes that are not UTF-8, which are none, and [InvalidUTF8At] is the question a caller asks
// before it takes text in. The other functions take text that is valid UTF-8 and do not ask it,
// apart from [ReadPattern], which refuses such text, and [Pattern.Matches], which accepts none.
package notation199x
