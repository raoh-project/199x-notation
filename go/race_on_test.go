//go:build race

package notation199x

// raceEnabled is whether the race detector is on, under which sync.Pool drops what it is handed
// at random, so a match makes its room again where it would have reused it.
const raceEnabled = true
