<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Symbols;

/**
 * The sets the pattern language names are written out as constants, so that no request works them
 * out. Each is held here to the algebra that states it.
 */
final class SymbolsTest extends TestCase
{
    public function testEachNamedSetIsWhatItsDefinitionWorksOutTo(): void
    {
        $everything = Symbols::not([]);
        $lineTerminators = Symbols::union(
            Symbols::one(0x0A),
            Symbols::one(0x0D),
            Symbols::one(0x85),
            Symbols::one(0x2028),
            Symbols::one(0x2029),
        );
        $digit = Symbols::between(0x30, 0x39);
        $word = Symbols::union(Symbols::between(0x61, 0x7A), Symbols::between(0x41, 0x5A), $digit, Symbols::one(0x5F));
        $space = Symbols::union(Symbols::one(0x20), Symbols::between(0x09, 0x0D));

        self::assertSame(Symbols::less($everything, $lineTerminators), Symbols::DOT);
        self::assertSame($digit, Symbols::DIGIT);
        self::assertSame($word, Symbols::WORD);
        self::assertSame($space, Symbols::SPACE);
        self::assertSame(Symbols::not($digit), Symbols::NOT_DIGIT);
        self::assertSame(Symbols::not($word), Symbols::NOT_WORD);
        self::assertSame(Symbols::not($space), Symbols::NOT_SPACE);
    }
}
