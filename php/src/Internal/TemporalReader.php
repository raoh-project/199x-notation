<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

/**
 * Reads the temporal forms left to right, a field at a time.
 *
 * @internal
 */
final class TemporalReader
{
    private int $at = 0;
    private readonly int $length;

    public function __construct(private readonly string $text)
    {
        $this->length = strlen($text);
    }

    public function done(): bool
    {
        return $this->at === $this->length;
    }

    /**
     * @phpstan-impure
     */
    public function take(string $c): bool
    {
        if ($this->at < $this->length && $this->text[$this->at] === $c) {
            $this->at++;
            return true;
        }
        return false;
    }

    /**
     * How many ASCII digits there are from where the reading is.
     */
    private function run(): int
    {
        return strspn($this->text, '0123456789', $this->at);
    }

    /**
     * Reads exactly $n digits, or null where there are not $n there.
     */
    private function number(int $n): ?int
    {
        if ($this->at + $n > $this->length || $this->run() < $n) {
            return null;
        }
        $value = 0;
        for ($i = 0; $i < $n; $i++) {
            $value = $value * 10 + (ord($this->text[$this->at + $i]) - 0x30);
        }
        $this->at += $n;
        return $value;
    }

    /**
     * Reads a year, a month and a day. A year from 0000 to 9999 is four digits and no sign; past
     * those it is a sign and five to ten digits with no leading zero; a minus and four digits is a
     * negative year other than 0000, so -0000 is not year 0.
     */
    public function date(TemporalFields $f): bool
    {
        $negative = $this->take('-');
        $signed = $negative || $this->take('+');
        $n = $this->run();
        $admitted = match (true) {
            !$signed && $n === 4 => true,
            $negative && $n === 4 => substr($this->text, $this->at, 4) !== '0000',
            $signed && $n >= 5 && $n <= 10 => $this->text[$this->at] !== '0',
            default => false,
        };
        if (!$admitted) {
            return false;
        }
        $year = $this->number($n) ?? 0;
        $f->year = $negative ? -$year : $year;
        if (!$this->take('-')) {
            return false;
        }
        $month = $this->number(2);
        if ($month === null || !$this->take('-')) {
            return false;
        }
        $f->month = $month;
        $day = $this->number(2);
        if ($day === null) {
            return false;
        }
        $f->day = $day;
        return true;
    }

    /**
     * Reads an hour and a minute, and the seconds and a fraction of one after them, which are
     * written where $withSeconds.
     */
    public function time(TemporalFields $f, bool $withSeconds): bool
    {
        $hour = $this->number(2);
        if ($hour === null || !$this->take(':')) {
            return false;
        }
        $minute = $this->number(2);
        if ($minute === null) {
            return false;
        }
        $f->hour = $hour;
        $f->minute = $minute;
        $f->second = -1;
        if (!$this->take(':')) {
            return !$withSeconds;
        }
        $second = $this->number(2);
        if ($second === null) {
            return false;
        }
        $f->second = $second;
        if ($this->take('.')) {
            $n = $this->run();
            if ($n < 1 || $n > 9) {
                return false;
            }
            $this->at += $n;
            $f->fraction = true;
        }
        return true;
    }

    /**
     * Reads Z, or a sign and hh:mm or hh:mm:ss.
     */
    public function offset(TemporalFields $f): bool
    {
        if ($this->take('Z')) {
            $f->utc = true;
            return true;
        }
        $f->offsetNegative = $this->take('-');
        if (!$f->offsetNegative && !$this->take('+')) {
            return false;
        }
        $hour = $this->number(2);
        if ($hour === null || !$this->take(':')) {
            return false;
        }
        $minute = $this->number(2);
        if ($minute === null) {
            return false;
        }
        $f->offsetHour = $hour;
        $f->offsetMinute = $minute;
        $f->offsetSecond = -1;
        if ($this->take(':')) {
            $second = $this->number(2);
            if ($second === null) {
                return false;
            }
            $f->offsetSecond = $second;
        }
        return true;
    }
}
