package notation199x

// TemporalKind is a temporal that has a text form.
type TemporalKind uint8

const (
	// Date is a day: yyyy-MM-dd.
	Date TemporalKind = iota
	// Time is a time of day: HH:mm or HH:mm:ss, with a fraction of a second or not.
	Time
	// DateTime is a day and a time of day, joined by T.
	DateTime
	// OffsetDateTime is a day, a time of day, and an offset from UTC: Z or ±HH:mm[:ss].
	OffsetDateTime
	// Instant is a moment: a day, a time of day with its seconds, and an offset from UTC.
	Instant
)

// TemporalAnswer is what [CheckTemporal] answers: that a text is admitted, or why it is not.
// The refusals are reasons and not a flag, because they are different things to tell a caller:
// text that is no temporal, and text that names a leap second.
type TemporalAnswer uint8

const (
	// Admitted is text that is a temporal of the kind asked about.
	Admitted TemporalAnswer = iota
	// Malformed is text that is not in the form, or is in it and names no day or moment.
	Malformed
	// LeapSecond is an instant whose second is 60.
	LeapSecond
)

const (
	// YearMin is the least year of a date.
	YearMin = -999_999_999
	// YearMax is the greatest year of a date.
	YearMax = 999_999_999
	// InstantMin is the least moment of an instant, in seconds from 1970-01-01T00:00:00Z: the
	// first second of year -1000000000.
	InstantMin = -31_557_014_167_219_200
	// InstantMax is the greatest moment of an instant, in seconds from 1970-01-01T00:00:00Z: the
	// last second of year 1000000000.
	InstantMax = 31_556_889_864_403_199
)

const (
	secondsPerDay = 86_400
	// maxOffsetSeconds is the furthest an offset reaches from UTC, eighteen hours.
	maxOffsetSeconds = 18 * 3600
	// daysToEpoch is the days from 0000-03-01 to 1970-01-01, which the day count is taken from.
	daysToEpoch = 719_468
)

// CheckTemporal is whether text is a temporal of kind, and why not where it is not one.
//
// Each form is a grammar over ASCII, and that grammar alone decides whether a text is in the form.
// Whether the fields then name a day and a moment that exist is arithmetic on the fields, done
// here, so the time package decides nothing: what is admitted does not follow a Go release. A
// value is built by whoever holds the text once it is admitted.
//
// Each temporal is admitted in its own domain. A date, a date-time and a date-time with an offset
// hold the years from [YearMin] to [YearMax] in the fields they are written in, whatever the
// offset. An instant holds the moments from [InstantMin] to [InstantMax], which reach a year
// further on either side and are counted in epoch seconds after the offset and an hour 24 have
// been applied.
//
// A date-time with an offset and an instant are two forms. The first is a local date-time beside
// a displacement: its seconds may be left out, its hour is one a clock shows, and it is admitted
// by the fields it writes. The second is a moment: its seconds are written, 24:00:00 with nothing
// after it is the start of the next day, and it is admitted by the moment it names.
//
// A time and a date-time may carry a fraction of a second of one to nine digits, as an instant
// may. The forms are ASCII, so a text that was admitted carries one exactly where it holds a full
// stop.
func CheckTemporal(kind TemporalKind, text string) TemporalAnswer {
	r := temporalReader{text: text}
	var f fields
	switch kind {
	case Date:
		if r.date(&f) && r.done() && f.dateExists() {
			return Admitted
		}
	case Time:
		if r.time(&f, false) && r.done() && f.timeExists() {
			return Admitted
		}
	case DateTime:
		if r.date(&f) && r.take('T') && r.time(&f, false) && r.done() && f.dateExists() && f.timeExists() {
			return Admitted
		}
	case OffsetDateTime:
		if r.date(&f) && r.take('T') && r.time(&f, false) && r.offset(&f) && r.done() &&
			f.dateExists() && f.timeExists() && f.offsetExists() {
			return Admitted
		}
	case Instant:
		if !(r.date(&f) && r.take('T') && r.time(&f, true) && r.offset(&f) && r.done() && f.offsetExists()) {
			return Malformed
		}
		if f.second == 60 {
			// A second that does not exist. Whether the moment it is said at does is asked with
			// the second every minute has.
			if f.momentExists(59) {
				return LeapSecond
			}
			return Malformed
		}
		if f.momentExists(f.second) {
			return Admitted
		}
	}
	return Malformed
}

// fields is what a temporal text writes, as numbers.
type fields struct {
	year                 int64
	month, day           int
	hour, minute, second int // second is -1 where it is not written
	fraction             bool
	utc                  bool
	offsetNegative       bool
	offsetHour           int
	offsetMinute         int
	offsetSecond         int // -1 where it is not written
}

// temporalReader reads the forms left to right, a field at a time.
type temporalReader struct {
	text string
	at   int
}

func (r *temporalReader) done() bool { return r.at == len(r.text) }

func (r *temporalReader) take(c byte) bool {
	if r.at < len(r.text) && r.text[r.at] == c {
		r.at++
		return true
	}
	return false
}

// run is how many ASCII digits there are from where the reading is.
func (r *temporalReader) run() int {
	n := 0
	for r.at+n < len(r.text) && isDigit(r.text[r.at+n]) {
		n++
	}
	return n
}

// number reads exactly n digits, and is false where there are not n there.
func (r *temporalReader) number(n int) (int64, bool) {
	if r.at+n > len(r.text) {
		return 0, false
	}
	var value int64
	for _, c := range []byte(r.text[r.at : r.at+n]) {
		if !isDigit(c) {
			return 0, false
		}
		value = value*10 + int64(c-'0')
	}
	r.at += n
	return value, true
}

func (r *temporalReader) two() (int, bool) {
	value, ok := r.number(2)
	return int(value), ok
}

// date reads a year, a month and a day. A year from 0000 to 9999 is four digits and no sign;
// past those it is a sign and five to ten digits with no leading zero; a minus and four digits is
// a negative year other than 0000, so -0000 is not year 0.
func (r *temporalReader) date(f *fields) bool {
	negative := r.take('-')
	signed := negative || r.take('+')
	n := r.run()
	switch {
	case !signed && n == 4:
	case negative && n == 4 && r.text[r.at:r.at+4] != "0000":
	case signed && n >= 5 && n <= 10 && r.text[r.at] != '0':
	default:
		return false
	}
	year, _ := r.number(n)
	if negative {
		year = -year
	}
	f.year = year
	var ok bool
	if !r.take('-') {
		return false
	}
	if f.month, ok = r.two(); !ok || !r.take('-') {
		return false
	}
	f.day, ok = r.two()
	return ok
}

// time reads an hour and a minute, and the seconds and a fraction of one after them, which are
// written where withSeconds.
func (r *temporalReader) time(f *fields, withSeconds bool) bool {
	var ok bool
	if f.hour, ok = r.two(); !ok || !r.take(':') {
		return false
	}
	if f.minute, ok = r.two(); !ok {
		return false
	}
	f.second = -1
	if !r.take(':') {
		return !withSeconds
	}
	if f.second, ok = r.two(); !ok {
		return false
	}
	if r.take('.') {
		n := r.run()
		if n < 1 || n > 9 {
			return false
		}
		r.at += n
		f.fraction = true
	}
	return true
}

// offset reads Z, or a sign and hh:mm or hh:mm:ss.
func (r *temporalReader) offset(f *fields) bool {
	if r.take('Z') {
		f.utc = true
		return true
	}
	f.offsetNegative = r.take('-')
	if !f.offsetNegative && !r.take('+') {
		return false
	}
	var ok bool
	if f.offsetHour, ok = r.two(); !ok || !r.take(':') {
		return false
	}
	if f.offsetMinute, ok = r.two(); !ok {
		return false
	}
	f.offsetSecond = -1
	if r.take(':') {
		f.offsetSecond, ok = r.two()
		return ok
	}
	return true
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }

// dateExists is whether the date is one there is: a year within the range, and a day the month
// has.
func (f *fields) dateExists() bool {
	return f.year >= YearMin && f.year <= YearMax && f.dayExists()
}

func (f *fields) dayExists() bool {
	return f.month >= 1 && f.month <= 12 && f.day >= 1 && f.day <= lengthOfMonth(f.year, f.month)
}

func lengthOfMonth(year int64, month int) int {
	switch month {
	case 2:
		if isLeap(year) {
			return 29
		}
		return 28
	case 4, 6, 9, 11:
		return 30
	default:
		return 31
	}
}

// isLeap is whether year of the proleptic Gregorian calendar has a 29 February.
func isLeap(year int64) bool {
	return year&3 == 0 && (year%100 != 0 || year%400 == 0)
}

// timeExists is whether the time of day is one a clock shows. Hour 24 is not, whatever follows.
func (f *fields) timeExists() bool {
	return f.hour <= 23 && f.minute <= 59 && f.second <= 59
}

// momentExists is whether the moment the text names is within the range of an instant, with
// second as its second.
//
// Counted in epoch seconds after the offset is taken off, so a year at either end of the range,
// an hour 24 that carries into the next day and an offset that carries into the last are asked
// of the moment they name and not of the fields they were written in. 24:00:00 is the start of
// the next day, and only when nothing follows it: an hour 24 with minutes or a fraction names no
// moment.
func (f *fields) momentExists(second int) bool {
	var timeExists bool
	if f.hour == 24 {
		timeExists = f.minute == 0 && second == 0 && !f.fraction
	} else {
		timeExists = f.hour <= 23 && f.minute <= 59 && second <= 59
	}
	if !timeExists || !f.dayExists() {
		return false
	}
	epochSecond := epochDay(f.year, f.month, f.day)*secondsPerDay +
		int64(f.hour)*3600 + int64(f.minute)*60 + int64(second) - int64(f.offsetSeconds())
	return epochSecond >= InstantMin && epochSecond <= InstantMax
}

// epochDay is the days from 1970-01-01 to a day of the proleptic Gregorian calendar that exists.
// The year runs from March, so the leap day is the last of it.
func epochDay(year int64, month, day int) int64 {
	shifted := year
	if month <= 2 {
		shifted--
	}
	era := floorDiv(shifted, 400)
	yearOfEra := shifted - era*400
	shiftedMonth := int64(month + 9)
	if month > 2 {
		shiftedMonth = int64(month - 3)
	}
	dayOfYear := (153*shiftedMonth+2)/5 + int64(day) - 1
	dayOfEra := yearOfEra*365 + yearOfEra/4 - yearOfEra/100 + dayOfYear
	return era*146_097 + dayOfEra - daysToEpoch
}

func floorDiv(a, b int64) int64 {
	q := a / b
	if (a%b != 0) && ((a < 0) != (b < 0)) {
		q--
	}
	return q
}

// offsetExists is whether the offset reaches no further than eighteen hours, with minutes and
// seconds that are those of a clock.
func (f *fields) offsetExists() bool {
	if f.utc {
		return true
	}
	seconds := f.offsetSeconds()
	if seconds < 0 {
		seconds = -seconds
	}
	return f.offsetMinute <= 59 && f.offsetSecond <= 59 && seconds <= maxOffsetSeconds
}

// offsetSeconds is the displacement from UTC the offset writes, in seconds; nought for Z.
func (f *fields) offsetSeconds() int {
	if f.utc {
		return 0
	}
	magnitude := f.offsetHour*3600 + f.offsetMinute*60 + max(f.offsetSecond, 0)
	if f.offsetNegative {
		return -magnitude
	}
	return magnitude
}
