package notation199x

import "strconv"

// Go has no closed enumeration, so each set of values here is an integer type, and a caller can
// convert any number to one. A number that is none of a type's constants is no value of it: it is
// never read as one of them. Where one is handed to a function it is a mistake in the calling
// program and the function panics, as for a nil map; where it is only printed, its String says
// what it is.

// nameOf is the name of value, or Type(n) where it is none of the type's values.
func nameOf(names []string, typ string, value uint8) string {
	if int(value) < len(names) {
		return names[value]
	}
	return typ + "(" + strconv.Itoa(int(value)) + ")"
}

// noneOf panics for a value of typ that is none of its constants, handed to a function.
func noneOf(typ string, value uint8) {
	panic("notation199x: " + typ + "(" + strconv.Itoa(int(value)) + ") is none of the " + typ + " values")
}

// unreachable panics where a value this package made is of a kind no case of a switch names: a
// kind added and not handled stops there rather than being taken for another.
func unreachable(what string, value uint8) {
	panic("notation199x: no case for " + what + " " + strconv.Itoa(int(value)))
}
