<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Tests\Support\Walks;

/**
 * Each case bench/walks.php times takes the path it is named for, so that what is measured under a
 * name is what the name says.
 */
final class WalksTest extends TestCase
{
    public function testEachCaseTakesThePathItIsNamedFor(): void
    {
        $room = Machine::$knownBytes;
        try {
            foreach (Walks::cases() as $name => $setUp) {
                [, $why] = $setUp();
                self::assertNull($why(), $name);
                Machine::$knownBytes = $room;
            }
        } finally {
            Machine::$knownBytes = $room;
        }
    }
}
