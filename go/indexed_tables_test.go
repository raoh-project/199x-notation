package notation199x

import (
	"os"
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"
)

// The tables a code point indexes, which the conversions and normalization ask as they read text,
// say of every code point what the database says, or what the searched tables they stand in front
// of say. They are held here to the database by a reading of it of their own, rather than taken on
// the generator's word.

// A stable starter of a form is a code point whose combining class is 0 and whose quick check for
// the form is Yes, and the combining class is the one UnicodeData.txt states.
func TestTheNormalizationTablesAreWhatTheDatabaseStates(t *testing.T) {
	classes := make(map[rune]uint8)
	data, err := os.ReadFile(repositoryFile(t, "ucd/18.0.0/UnicodeData.txt"))
	if err != nil {
		t.Fatal(err)
	}
	for _, text := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		f := strings.Split(text, ";")
		r, _ := strconv.ParseUint(f[0], 16, 32)
		class, _ := strconv.ParseUint(f[3], 10, 8)
		if class != 0 {
			classes[rune(r)] = uint8(class)
		}
	}
	notYes := map[string]map[rune]bool{}
	data, err = os.ReadFile(repositoryFile(t, "ucd/18.0.0/DerivedNormalizationProps.txt"))
	if err != nil {
		t.Fatal(err)
	}
	for _, text := range strings.Split(string(data), "\n") {
		if hash := strings.IndexByte(text, '#'); hash >= 0 {
			text = text[:hash]
		}
		f := strings.Split(text, ";")
		if len(f) != 3 || !strings.HasSuffix(strings.TrimSpace(f[1]), "_QC") || strings.TrimSpace(f[2]) == "Y" {
			continue
		}
		form := strings.TrimSuffix(strings.TrimSpace(f[1]), "_QC")
		if notYes[form] == nil {
			notYes[form] = map[rune]bool{}
		}
		first, last, _ := strings.Cut(strings.TrimSpace(f[0]), "..")
		from, _ := strconv.ParseUint(first, 16, 32)
		to := from
		if last != "" {
			to, _ = strconv.ParseUint(last, 16, 32)
		}
		for r := from; r <= to; r++ {
			notYes[form][rune(r)] = true
		}
	}
	if len(notYes) != 4 {
		t.Fatalf("the quick checks read are %d", len(notYes))
	}
	wrong := 0
	for r := rune(0); r <= utf8.MaxRune; r++ {
		if combiningClass(r) != classes[r] {
			wrong++
			t.Errorf("the combining class of U+%04X is %d, not %d", r, combiningClass(r), classes[r])
		}
		for _, form := range []Form{NFC, NFD, NFKC, NFKD} {
			stated := classes[r] == 0 && !notYes[form.String()][r]
			if form.facts().stable(r) != stated {
				wrong++
				t.Errorf("U+%04X is a stable starter of %v: %v", r, form, !stated)
			}
		}
		if wrong > 20 {
			t.Fatal("and more")
		}
	}
}

// Each case table answers 0 where the searched mapping has nothing for a code point or maps it to
// itself, and otherwise one more than where the searched mapping holds it.
func TestTheCaseTablesAnswerWhereTheMappingHoldsWhatChanges(t *testing.T) {
	for _, each := range []struct {
		name    string
		mapping mapping
		blocks  *[4352]uint8
		pages   []uint16
	}{
		{"lower", lowerMapping, &lowerPositionBlocks, lowerPositionPages[:]},
		{"upper", upperMapping, &upperPositionBlocks, upperPositionPages[:]},
	} {
		at := make(map[rune]int)
		for i, m := range each.mapping {
			if len(m.to) != 1 || m.to[0] != m.from {
				at[m.from] = i + 1
			}
		}
		for r := rune(0); r <= utf8.MaxRune; r++ {
			if got := int(each.pages[int(each.blocks[r>>8])<<8|int(r&0xFF)]); got != at[r] {
				t.Fatalf("%s U+%04X is %d, not %d", each.name, r, got, at[r])
			}
		}
	}
	for _, m := range finalSigmaMapping {
		if lowerPositionPages[int(lowerPositionBlocks[m.from>>8])<<8|int(m.from&0xFF)] == 0 {
			t.Errorf("U+%04X has a Final_Sigma mapping and the lowercase table maps it to itself", m.from)
		}
	}
}

// Every decomposition fits the room made for one: longestDecomposition is the longest there is, in
// any form.
func TestTheRoomForADecompositionIsTheLongestThereIs(t *testing.T) {
	longest := 0
	var parts []rune
	for r := rune(0); r <= utf8.MaxRune; r++ {
		var decomposed bool
		if parts, decomposed = decomposeInto(parts[:0], r, true); !decomposed {
			parts = append(parts[:0], r)
		}
		longest = max(longest, len(parts))
	}
	if longest != longestDecomposition {
		t.Errorf("the longest decomposition is %d code points, and longestDecomposition is %d", longest,
			longestDecomposition)
	}
}
