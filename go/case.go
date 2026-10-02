package notation199x

import (
	"strings"
	"unicode/utf8"
)

// Lowercase is s in lowercase: Unicode 18.0.0's untailored full mapping, from UnicodeData.txt and
// SpecialCasing.txt, with no locale or language tailoring. One code point can map to several. A
// Greek capital sigma becomes the final form only at the end of a cased run, which is the one
// condition the default mapping carries that is context rather than locale: Lowercase("ΟΣ") is
// "ος" and Lowercase("ΟΣΑ") is "οσα".
//
// Not [strings.ToLower], which maps by the Unicode version of the Go release, one code point to
// one, and has no Final_Sigma.
//
// A case mapping is not closed under any normalization form, so a caller that holds its text in
// one normalizes the answer. s is valid UTF-8 (see [InvalidUTF8At]).
func Lowercase(s string) string {
	mapped, _ := mapCase(s, true, -1)
	return mapped
}

// Uppercase is s in uppercase, by the same untailored full mapping as [Lowercase]: one code
// point can widen to several, so Uppercase("straße") is "STRASSE", and no locale narrows it, so a
// Turkish i still becomes I. s is valid UTF-8 (see [InvalidUTF8At]).
func Uppercase(s string) string {
	mapped, _ := mapCase(s, false, -1)
	return mapped
}

// LowercaseWithin is [Lowercase] where that is no longer than longest scalar values, and false
// where it is longer, which is found out before more than longest is written. A negative bound
// is one no text is within.
func LowercaseWithin(s string, longest int) (string, bool) {
	if longest < 0 {
		return "", false
	}
	return mapCase(s, true, longest)
}

// UppercaseWithin is [Uppercase] where that is no longer than longest scalar values, and false
// where it is longer, which is found out before more than longest is written. A negative bound
// is one no text is within.
func UppercaseWithin(s string, longest int) (string, bool) {
	if longest < 0 {
		return "", false
	}
	return mapCase(s, false, longest)
}

// mapCase is the mapped text, and false where it is longer than longest; a negative longest is
// no bound.
//
// The text is read where it is: Final_Sigma looks either side of a sigma for as many
// Case_Ignorable code points as there are, and looks at them in the text. What is bounded is what
// is written: each code point's mapping is measured before any of it is, so the answer never
// holds more than longest, nor part of a mapping that would take it past.
//
// Text that maps to itself is answered with itself. Otherwise what maps to itself is copied a run
// at a time, from kept, and the answer is made only once a code point that changes is met.
func mapCase(s string, lower bool, longest int) (string, bool) {
	table, ascii := upperMapping, &caseOfASCII[1]
	if lower {
		table, ascii = lowerMapping, &caseOfASCII[0]
	}
	var out strings.Builder
	// changed is whether a code point that changes has been met, and out holds the answer up to
	// kept.
	changed := false
	kept, written := 0, 0
	for at := 0; at < len(s); {
		if c := s[at]; c < utf8.RuneSelf && ascii[c] >= 0 {
			if longest >= 0 && written >= longest {
				return "", false
			}
			written++
			if mapped := byte(ascii[c]); mapped != c {
				if !changed {
					out.Grow(room(len(s), longest))
					changed = true
				}
				out.WriteString(s[kept:at])
				out.WriteByte(mapped)
				kept = at + 1
			}
			at++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[at:])
		after := at + size
		var to []rune
		if lower {
			if final := finalSigmaMapping.of(r); final != nil && isFinalSigma(s, at, after) {
				to = final
			}
		}
		if to == nil {
			to = table.of(r)
		}
		adding := len(to)
		if to == nil {
			adding = 1
		}
		if longest >= 0 && adding > longest-written {
			return "", false
		}
		written += adding
		if to != nil {
			if !changed {
				out.Grow(room(len(s), longest))
				changed = true
			}
			out.WriteString(s[kept:at])
			for _, each := range to {
				out.WriteRune(each)
			}
			kept = after
		}
		at = after
	}
	if !changed {
		return s, true
	}
	out.WriteString(s[kept:])
	return out.String(), true
}

// caseOfASCII is, for the lowercase and the uppercase mapping, what each ASCII character maps to
// where the mapping makes it one ASCII character and no Final_Sigma entry names it, and -1 where
// the tables are asked. Read off the tables, so that most text is mapped a byte at a time without
// a search and without a rule of its own about ASCII.
var caseOfASCII = func() (out [2][utf8.RuneSelf]int16) {
	for c := rune(0); c < utf8.RuneSelf; c++ {
		for i, table := range []mapping{lowerMapping, upperMapping} {
			out[i][c] = -1
			if i == 0 && finalSigmaMapping.of(c) != nil {
				continue
			}
			switch to := table.of(c); {
			case to == nil:
				out[i][c] = int16(c)
			case len(to) == 1 && to[0] < utf8.RuneSelf:
				out[i][c] = int16(to[0])
			}
		}
	}
	return out
}()

// isFinalSigma is Unicode's Final_Sigma condition of the code point between at and after:
// preceded, skipping Case_Ignorable code points, by a Cased one, and not followed, skipping the
// same way, by another Cased one. Scanned as far as the text goes rather than over a window,
// since what is skipped is decided by the property and not by a count.
func isFinalSigma(s string, at, after int) bool {
	precededByCased := false
	for j := at; j > 0; {
		r, size := utf8.DecodeLastRuneInString(s[:j])
		j -= size
		if caseIgnorableRanges.has(r) {
			continue
		}
		precededByCased = casedRanges.has(r)
		break
	}
	if !precededByCased {
		return false
	}
	for j := after; j < len(s); {
		r, size := utf8.DecodeRuneInString(s[j:])
		j += size
		if caseIgnorableRanges.has(r) {
			continue
		}
		return !casedRanges.has(r)
	}
	return true
}
