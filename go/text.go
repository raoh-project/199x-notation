package notation199x

import "unicode/utf8"

// InvalidUTF8At is where s holds bytes that are not UTF-8, as the byte index the first such
// sequence begins at, or -1 where s is valid UTF-8 and so is a sequence of scalar values.
//
// A surrogate written in UTF-8's form, such as the bytes ED A0 80, is not UTF-8: a surrogate is
// half of a UTF-16 pair and no scalar value.
func InvalidUTF8At(s string) int {
	for at := 0; at < len(s); {
		if s[at] < utf8.RuneSelf {
			at++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[at:])
		if r == utf8.RuneError && size == 1 {
			return at
		}
		at += size
	}
	return -1
}

// ScalarCount is how many scalar values s is made of, which is the length every rule here
// measures text in, and not len(s), which counts bytes.
//
// s is valid UTF-8 (see [InvalidUTF8At]); this does not ask.
func ScalarCount(s string) int {
	return utf8.RuneCountInString(s)
}

// Compare is where a stands against b: the first scalar value where they differ decides, and
// where one is a prefix of the other the shorter is below. It answers -1, 0 or +1 as a stands
// below, with or above b.
//
// The order of UTF-8 bytes is the order of the scalar values they encode, so on valid UTF-8 this
// is the order of the strings as Go compares them. a and b are valid UTF-8 (see
// [InvalidUTF8At]); this does not ask.
func Compare(a, b string) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}
