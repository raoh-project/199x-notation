<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests\Support;

use PHPUnit\Framework\Assert;
use Raoh\Notation199x\Internal\Utf8;

/**
 * One line of vectors, and which of its fields have been read. A field that is not written as the
 * format says fails the test where it is read.
 */
final class Line
{
    /** @var array<int, bool> */
    private array $read = [];

    /**
     * @param list<string> $fields
     */
    public function __construct(private readonly string $where, private readonly array $fields)
    {
    }

    private function take(int $i): string
    {
        $this->read[$i] = true;
        return $this->fields[$i];
    }

    private function wrong(int $i, string $what): never
    {
        Assert::fail(sprintf('%s: field %d is "%s", not %s', $this->where, $i + 1, $this->fields[$i], $what));
    }

    /**
     * Reads a field a line leaves empty where it asserts nothing there.
     */
    public function empty(int $i): void
    {
        if ($this->take($i) !== '') {
            $this->wrong($i, 'empty');
        }
    }

    /**
     * Reads a field as the text it writes: scalar values in hex, separated by spaces.
     */
    public function text(int $i): string
    {
        $field = $this->take($i);
        if ($field === '') {
            return '';
        }
        $text = '';
        foreach (preg_split('/ +/', $field) ?: [] as $each) {
            if (preg_match('/^[0-9A-F]{4,6}$/', $each) !== 1) {
                $this->wrong($i, 'a text of scalar values in hex');
            }
            $value = (int) hexdec($each);
            if ($value > 0x10FFFF || $value >= 0xD800 && $value <= 0xDFFF) {
                $this->wrong($i, 'a text of scalar values in hex');
            }
            $text .= Utf8::encode($value);
        }
        return $text;
    }

    /**
     * Reads a field as the one scalar value it writes.
     */
    public function scalar(int $i): int
    {
        $text = $this->text($i);
        $characters = Utf8::split($text);
        if (count($characters) !== 1) {
            $this->wrong($i, 'one scalar value');
        }
        return Utf8::decode($characters[0]);
    }

    /**
     * Reads a field as an unsigned decimal.
     */
    public function number(int $i): int
    {
        $field = $this->take($i);
        if (preg_match('/^(0|[1-9][0-9]{0,17})$/', $field) !== 1) {
            $this->wrong($i, 'an unsigned decimal');
        }
        return (int) $field;
    }

    /**
     * Reads a field as an unsigned decimal, or null where it is empty.
     */
    public function numberOrNothing(int $i): ?int
    {
        if ($this->fields[$i] === '') {
            $this->empty($i);
            return null;
        }
        return $this->number($i);
    }

    /**
     * Reads a field as one of $names.
     */
    public function oneOf(int $i, string ...$names): string
    {
        $field = $this->take($i);
        if (!in_array($field, $names, true)) {
            $this->wrong($i, 'one of ' . implode(', ', $names));
        }
        return $field;
    }

    /**
     * Reads a field as one of $names, or null where it is empty.
     */
    public function oneOfOrNothing(int $i, string ...$names): ?string
    {
        if ($this->fields[$i] === '') {
            $this->empty($i);
            return null;
        }
        return $this->oneOf($i, ...$names);
    }

    /**
     * Reads a field as true or false.
     */
    public function yesOrNo(int $i): bool
    {
        return $this->oneOf($i, 'true', 'false') === 'true';
    }

    /**
     * Fails where a field of the line was not read.
     */
    public function allRead(): void
    {
        foreach (array_keys($this->fields) as $i) {
            Assert::assertTrue($this->read[$i] ?? false, sprintf('%s: field %d is not read', $this->where, $i + 1));
        }
    }
}
