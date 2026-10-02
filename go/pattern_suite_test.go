package notation199x

import (
	"fmt"
	"strconv"
	"testing"
)

var limitNames = map[string]PatternLimit{
	"REPETITION_COUNT": RepetitionCount, "NESTING_DEPTH": NestingDepth, "MACHINE_STATES": MachineStates,
}

func TestPatternsAreReadAsEveryLineOfTheSuiteSays(t *testing.T) {
	eachLine(t, "pattern-read.txt", 3, func(l *line) string {
		pattern := l.text(0)
		outcome := l.oneOf(1, "READ", "REFUSED", "BEYOND")
		limit := ""
		if outcome == "BEYOND" {
			limit = l.oneOfOrNothing(2, "REPETITION_COUNT", "NESTING_DEPTH", "MACHINE_STATES")
		} else {
			l.empty(2)
		}
		var asSaid bool
		switch answered := ReadPattern(pattern).(type) {
		case *Pattern:
			asSaid = outcome == "READ"
		case PatternRefused:
			asSaid = outcome == "REFUSED"
		case PatternBeyond:
			asSaid = outcome == "BEYOND" && (limit == "" || answered.Limit == limitNames[limit])
		}
		if !asSaid {
			return fmt.Sprintf("%s is %#v, not %s %s", shown(pattern), ReadPattern(pattern), outcome, limit)
		}
		return ""
	})
}

func TestPatternsAcceptWhatEveryLineOfTheSuiteSays(t *testing.T) {
	eachLine(t, "pattern-match.txt", 3, func(l *line) string {
		pattern := l.text(0)
		subject := l.text(1)
		accepted := l.yesOrNo(2)
		read, ok := ReadPattern(pattern).(*Pattern)
		if !ok {
			return fmt.Sprintf("%s is not read: %#v", shown(pattern), ReadPattern(pattern))
		}
		if read.Matches(subject) != accepted {
			return fmt.Sprintf("%s accepts %s: %v", shown(pattern), shown(subject), !accepted)
		}
		return ""
	})
}

// The states the specifications state the limit in, which the file's rule is written against: not
// the one this implementation holds, which is what is tested.
const mostStates = 250_000

func TestPatternStatesAreCountedAsEveryLineOfTheSuiteSays(t *testing.T) {
	eachLine(t, "pattern-states.txt", 2, func(l *line) string {
		pattern := l.text(0)
		states := l.number(1)
		at := ReadPattern("(?:" + pattern + ")|a{0," + strconv.Itoa(mostStates-5-states) + "}")
		past := ReadPattern("(?:" + pattern + ")|a{0," + strconv.Itoa(mostStates-4-states) + "}")
		if _, ok := at.(*Pattern); !ok {
			return fmt.Sprintf("%s at the limit is %#v", shown(pattern), at)
		}
		if beyond, ok := past.(PatternBeyond); !ok || beyond.Limit != MachineStates {
			return fmt.Sprintf("%s past the limit is %#v", shown(pattern), past)
		}
		return ""
	})
}
