package notation199x

// IsWhiteSpace is whether r has the Unicode White_Space property, as of Unicode 18.0.0: twenty-five
// code points.
//
// The set is generated from the database's PropList.txt rather than read off [unicode.White_Space],
// which is the set of the Unicode version the Go release carries.
func IsWhiteSpace(r rune) bool {
	// A handful of ranges, so a scan in order is as quick as a search.
	for _, each := range whiteSpaceRanges {
		if r < each.first {
			return false
		}
		if r <= each.last {
			return true
		}
	}
	return false
}
