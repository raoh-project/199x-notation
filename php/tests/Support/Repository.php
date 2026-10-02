<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests\Support;

use PHPUnit\Framework\Assert;
use PHPUnit\Framework\TestCase;

/**
 * The files of the repository outside this package: the vectors in its suite directory and the
 * database files in its ucd directory. A package made from php/ holds what is under it, so a copy
 * Composer installed has neither. A test that reads them runs in a checkout, skips where they are
 * not there, and fails instead where NOTATION199X_REQUIRE_SUITE is set, as CI sets it, so that a
 * checkout missing them is not taken for a package without them.
 */
final class Repository
{
    private const REQUIRE_SUITE = 'NOTATION199X_REQUIRE_SUITE';

    /**
     * The contents of $name under the repository's root, or a skip where the file is not there and
     * the environment does not require it.
     */
    public static function read(string $name): string
    {
        $path = dirname(__DIR__, 3) . '/' . $name;
        if (!is_file($path)) {
            if ((string) getenv(self::REQUIRE_SUITE) !== '') {
                Assert::fail("$path is missing, and " . self::REQUIRE_SUITE . ' is set: the tests run in php/ of a checkout');
            }
            TestCase::markTestSkipped("$path is outside this package, and is read only in a checkout of the repository");
        }
        $contents = file_get_contents($path);
        Assert::assertIsString($contents);
        return $contents;
    }

    /**
     * Holds an implementation to every line of $name in the suite, as suite/README.md states the
     * format: $check reads every field of a line and answers what is wrong on it, or null. A line
     * is read whole or not at all, and a field no check reads fails the test, so nothing written
     * in a file goes unchecked.
     *
     * @param callable(Line): ?string $check
     */
    public static function eachLine(string $name, int $fields, callable $check): void
    {
        $lines = explode("\n", rtrim(self::read('suite/' . $name), "\n"));
        $sourced = false;
        $vectors = 0;
        $wrong = [];
        foreach ($lines as $n => $text) {
            if ($text === '# Source:' && str_starts_with($lines[$n + 1] ?? '', '#   ')) {
                $sourced = true;
            }
            if ($text === '' || $text[0] === '#') {
                continue;
            }
            $where = $name . ':' . ($n + 1);
            Assert::assertTrue($sourced, "$name names no source before its first vector");
            $split = explode(';', $text);
            Assert::assertCount($fields, $split, "$where has the wrong number of fields");
            $line = new Line($where, array_map('trim', $split));
            $said = $check($line);
            $line->allRead();
            if ($said !== null) {
                $wrong[] = "$where: $said";
            }
            $vectors++;
        }
        Assert::assertGreaterThan(0, $vectors, "$name holds no vectors");
        Assert::assertSame([], $wrong);
    }

    /**
     * $text as a message shows it: printable ASCII as itself, and every other scalar value as
     * <U+XXXX>.
     */
    public static function shown(string $text): string
    {
        $out = '"';
        foreach (preg_split('//u', $text, -1, PREG_SPLIT_NO_EMPTY) ?: [] as $character) {
            $cp = \Raoh\Notation199x\Internal\Utf8::decode($character);
            $out .= $cp >= 0x20 && $cp < 0x7F ? $character : sprintf('<U+%04X>', $cp);
        }
        return $out . '"';
    }
}
