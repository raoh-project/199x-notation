<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Symbols;
use Raoh\Notation199x\Internal\Pattern\SymbolSets;

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

    /**
     * A set of one code point and a set the language names are held once, by a key that is the
     * set; every other set is held as written, and no two sets share a key. These two classes'
     * ranges written out have the same CRC-32, which no answer here turns on.
     */
    public function testASetIsHeldOnceOnlyByAKeyThatIsTheSet(): void
    {
        $sets = new SymbolSets();
        $a = [0x10339, 0x1033A];
        $b = [0x19337, 0x19338];
        self::assertSame(crc32(implode(',', $a)), crc32(implode(',', $b)));

        $first = [$sets->add($a), $sets->add($b), $sets->add($b), $sets->add($a)];
        self::assertSame([0, 1, 2, 3], $first, 'a class is held as written');
        self::assertSame([$a, $b, $b, $a], array_map($sets->held(...), $first));

        $x = $sets->add(Symbols::one(0x78));
        $dot = $sets->add(Symbols::DOT);
        $digit = $sets->add(Symbols::between(0x30, 0x39));
        self::assertSame($x, $sets->add(Symbols::one(0x78)));
        self::assertSame($dot, $sets->add(Symbols::DOT));
        self::assertSame($digit, $sets->add(Symbols::DIGIT), 'a class that is a named set is held as that set');
        self::assertNotSame($x, $sets->add(Symbols::one(0x79)));
        // What the sets take is what was written: four classes of one range, three sets held once,
        // and one more of one code point.
        self::assertSame(2 * 4 + 2 + count(Symbols::DOT) + 2 + 2, count($sets->ranges));

        $sets->seal();
        $this->expectException(\LogicException::class);
        $sets->add($a);
    }
}
