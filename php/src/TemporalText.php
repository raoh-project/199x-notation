<?php

declare(strict_types=1);

namespace Raoh\Notation199x;

use Raoh\Notation199x\Internal\TemporalFields;
use Raoh\Notation199x\Internal\TemporalReader;

/**
 * The lexical grammar of dates, times, date-times, date-times with an offset, and instants.
 */
final class TemporalText
{
    /** The least year of a date. */
    public const YEAR_MIN = -999_999_999;
    /** The greatest year of a date. */
    public const YEAR_MAX = 999_999_999;
    /**
     * The least moment of an instant, in seconds from 1970-01-01T00:00:00Z: the first second of
     * year -1000000000.
     */
    public const INSTANT_MIN = -31_557_014_167_219_200;
    /**
     * The greatest moment of an instant, in seconds from 1970-01-01T00:00:00Z: the last second of
     * year 1000000000.
     */
    public const INSTANT_MAX = 31_556_889_864_403_199;

    private function __construct()
    {
    }

    /**
     * Whether $text is a temporal of $kind, and why not where it is not one.
     *
     * Each form is a grammar over ASCII, and that grammar alone decides whether a text is in the
     * form. It is read left to right a field at a time, and not by a regular expression. Whether
     * the fields then name a day and a moment that exist is arithmetic on the fields, done here,
     * so neither DateTimeImmutable nor date_parse decides anything: what is admitted does not
     * follow a PHP release. A value is built by whoever holds the text once it is admitted.
     *
     * Each temporal is admitted in its own domain. A date, a date-time and a date-time with an
     * offset hold the years from YEAR_MIN to YEAR_MAX in the fields they are written in, whatever
     * the offset. An instant holds the moments from INSTANT_MIN to INSTANT_MAX, which reach a year
     * further on either side and are counted in epoch seconds after the offset and an hour 24 have
     * been applied.
     *
     * A date-time with an offset and an instant are two forms. The first is a local date-time
     * beside a displacement: its seconds may be left out, its hour is one a clock shows, and it is
     * admitted by the fields it writes. The second is a moment: its seconds are written, 24:00:00
     * with nothing after it is the start of the next day, and it is admitted by the moment it
     * names.
     *
     * A time and a date-time may carry a fraction of a second of one to nine digits, as an instant
     * may. The forms are ASCII, so a text that was admitted carries one exactly where it holds a
     * full stop. $text may be any string: bytes that are not ASCII are in no form.
     */
    public static function check(TemporalKind $kind, string $text): TemporalAnswer
    {
        $r = new TemporalReader($text);
        $f = new TemporalFields();
        switch ($kind) {
            case TemporalKind::Date:
                if ($r->date($f) && $r->done() && $f->dateExists()) {
                    return TemporalAnswer::Admitted;
                }
                break;
            case TemporalKind::Time:
                if ($r->time($f, false) && $r->done() && $f->timeExists()) {
                    return TemporalAnswer::Admitted;
                }
                break;
            case TemporalKind::DateTime:
                if ($r->date($f) && $r->take('T') && $r->time($f, false) && $r->done()
                    && $f->dateExists() && $f->timeExists()) {
                    return TemporalAnswer::Admitted;
                }
                break;
            case TemporalKind::OffsetDateTime:
                if ($r->date($f) && $r->take('T') && $r->time($f, false) && $r->offset($f) && $r->done()
                    && $f->dateExists() && $f->timeExists() && $f->offsetExists()) {
                    return TemporalAnswer::Admitted;
                }
                break;
            case TemporalKind::Instant:
                if (!($r->date($f) && $r->take('T') && $r->time($f, true) && $r->offset($f) && $r->done()
                    && $f->offsetExists())) {
                    return TemporalAnswer::Malformed;
                }
                if ($f->second === 60) {
                    // A second that does not exist. Whether the moment it is said at does is asked
                    // with the second every minute has.
                    return $f->momentExists(59) ? TemporalAnswer::LeapSecond : TemporalAnswer::Malformed;
                }
                if ($f->momentExists($f->second)) {
                    return TemporalAnswer::Admitted;
                }
                break;
        }
        return TemporalAnswer::Malformed;
    }
}
