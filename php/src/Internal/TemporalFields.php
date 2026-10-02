<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

use Raoh\Notation199x\TemporalText;

/**
 * What a temporal text writes, as numbers, and whether the day, the time and the moment they name
 * exist.
 *
 * @internal
 */
final class TemporalFields
{
    private const SECONDS_PER_DAY = 86_400;
    /** The furthest an offset reaches from UTC, eighteen hours. */
    private const MAX_OFFSET_SECONDS = 18 * 3600;
    /** The days from 0000-03-01 to 1970-01-01, which the day count is taken from. */
    private const DAYS_TO_EPOCH = 719_468;

    public int $year = 0;
    public int $month = 0;
    public int $day = 0;
    public int $hour = 0;
    public int $minute = 0;
    /** -1 where it is not written. */
    public int $second = -1;
    public bool $fraction = false;
    public bool $utc = false;
    public bool $offsetNegative = false;
    public int $offsetHour = 0;
    public int $offsetMinute = 0;
    /** -1 where it is not written. */
    public int $offsetSecond = -1;

    /**
     * Whether the date is one there is: a year within the range, and a day the month has.
     */
    public function dateExists(): bool
    {
        return $this->year >= TemporalText::YEAR_MIN && $this->year <= TemporalText::YEAR_MAX && $this->dayExists();
    }

    private function dayExists(): bool
    {
        return $this->month >= 1 && $this->month <= 12
            && $this->day >= 1 && $this->day <= self::lengthOfMonth($this->year, $this->month);
    }

    private static function lengthOfMonth(int $year, int $month): int
    {
        return match ($month) {
            2 => self::isLeap($year) ? 29 : 28,
            4, 6, 9, 11 => 30,
            default => 31,
        };
    }

    /**
     * Whether $year of the proleptic Gregorian calendar has a 29 February.
     */
    private static function isLeap(int $year): bool
    {
        return ($year & 3) === 0 && ($year % 100 !== 0 || $year % 400 === 0);
    }

    /**
     * Whether the time of day is one a clock shows. Hour 24 is not, whatever follows.
     */
    public function timeExists(): bool
    {
        return $this->hour <= 23 && $this->minute <= 59 && $this->second <= 59;
    }

    /**
     * Whether the moment the text names is within the range of an instant, with $second as its
     * second.
     *
     * Counted in epoch seconds after the offset is taken off, so a year at either end of the
     * range, an hour 24 that carries into the next day and an offset that carries into the last
     * are asked of the moment they name and not of the fields they were written in. 24:00:00 is
     * the start of the next day, and only when nothing follows it: an hour 24 with minutes or a
     * fraction names no moment.
     */
    public function momentExists(int $second): bool
    {
        if ($this->hour === 24) {
            $timeExists = $this->minute === 0 && $second === 0 && !$this->fraction;
        } else {
            $timeExists = $this->hour <= 23 && $this->minute <= 59 && $second <= 59;
        }
        if (!$timeExists || !$this->dayExists()) {
            return false;
        }
        $epochSecond = self::epochDay($this->year, $this->month, $this->day) * self::SECONDS_PER_DAY
            + $this->hour * 3600 + $this->minute * 60 + $second - $this->offsetSeconds();
        return $epochSecond >= TemporalText::INSTANT_MIN && $epochSecond <= TemporalText::INSTANT_MAX;
    }

    /**
     * The days from 1970-01-01 to a day of the proleptic Gregorian calendar that exists. The year
     * runs from March, so the leap day is the last of it.
     */
    private static function epochDay(int $year, int $month, int $day): int
    {
        $shifted = $month <= 2 ? $year - 1 : $year;
        $era = intdiv($shifted, 400) - ($shifted % 400 < 0 ? 1 : 0);
        $yearOfEra = $shifted - $era * 400;
        $shiftedMonth = $month > 2 ? $month - 3 : $month + 9;
        $dayOfYear = intdiv(153 * $shiftedMonth + 2, 5) + $day - 1;
        $dayOfEra = $yearOfEra * 365 + intdiv($yearOfEra, 4) - intdiv($yearOfEra, 100) + $dayOfYear;
        return $era * 146_097 + $dayOfEra - self::DAYS_TO_EPOCH;
    }

    /**
     * Whether the offset reaches no further than eighteen hours, with minutes and seconds that are
     * those of a clock.
     */
    public function offsetExists(): bool
    {
        if ($this->utc) {
            return true;
        }
        return $this->offsetMinute <= 59 && $this->offsetSecond <= 59
            && abs($this->offsetSeconds()) <= self::MAX_OFFSET_SECONDS;
    }

    /**
     * The displacement from UTC the offset writes, in seconds; nought for Z.
     */
    private function offsetSeconds(): int
    {
        if ($this->utc) {
            return 0;
        }
        $magnitude = $this->offsetHour * 3600 + $this->offsetMinute * 60 + max($this->offsetSecond, 0);
        return $this->offsetNegative ? -$magnitude : $magnitude;
    }
}
