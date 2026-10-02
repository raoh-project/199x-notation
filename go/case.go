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
func mapCase(s string, lower bool, longest int) (string, bool) {
	var out strings.Builder
	out.Grow(room(len(s), longest))
	written := 0
	for at := 0; at < len(s); {
		r, size := utf8.DecodeRuneInString(s[at:])
		after := at + size
		var to []rune
		if lower {
			if final := finalSigmaMapping.of(r); final != nil && isFinalSigma(s, at, after) {
				to = final
			}
		}
		if to == nil {
			if lower {
				to = lowerMapping.of(r)
			} else {
				to = upperMapping.of(r)
			}
		}
		adding := len(to)
		if to == nil {
			adding = 1
		}
		if longest >= 0 && adding > longest-written {
			return "", false
		}
		written += adding
		if to == nil {
			out.WriteString(s[at:after])
		} else {
			for _, each := range to {
				out.WriteRune(each)
			}
		}
		at = after
	}
	return out.String(), true
}

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
