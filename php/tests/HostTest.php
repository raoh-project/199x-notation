<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;

/**
 * The rules ask nothing of the PHP they run on that answers for a Unicode version or for a parser
 * of its own: no mbstring, no intl, no iconv, no date parsing, and PCRE only to be told whether
 * bytes are UTF-8, which follows no Unicode version. CI runs the tests without intl as well.
 * PHPUnit needs mbstring, so this is what holds the sources to not asking it.
 */
final class HostTest extends TestCase
{
    /** What the sources may not name, as the start of a function or class name, in lower case. */
    private const ASKED_NOTHING = [
        'mb_', 'iconv', 'grapheme_', 'normalizer', 'intlchar', 'transliterator', 'collator', 'idn_',
        'datetime', 'date_', 'strtotime', 'checkdate', 'mktime', 'gmmktime', 'strtolower', 'strtoupper',
        'ucfirst', 'lcfirst', 'ucwords', 'ctype_', 'utf8_', 'html_entity_decode',
    ];

    /** The one PCRE call the sources make, and where. */
    private const PCRE_ASKED = ['ScalarValues.php' => 1];

    /**
     * What a class holds from one call to the next, by class and property, and why it may. PHP
     * keeps it for a request where PHP-FPM serves one, and keeps none of it for the next, so what
     * is worked out here is worked out again on every request: a table that is a fact about Unicode
     * is generated instead (see gen/PhpEmitter.java), and what is held here costs a request a few
     * microseconds or grows only as far as the tables do.
     */
    private const STATE_HELD = [
        'Raoh\\Notation199x\\CaseConversion::$stops' => 'two strings of the ASCII characters the tables name, 128 lookups each',
        'Raoh\\Notation199x\\Internal\\Composing::$decompositions' => 'the full decompositions asked for, no more than the tables hold',
        'Raoh\\Notation199x\\Internal\\Pattern\\Machine::$knownBytes' => 'the room a machine keeps sets in, which a test sets',
    ];

    /**
     * Nothing holds state from one call to the next but what STATE_HELD names. A static property
     * that is not there is a table or a cache someone added without asking what it costs a request.
     */
    public function testWhatIsHeldFromOneCallToTheNextIsWhatTheListSays(): void
    {
        $held = [];
        $files = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator(dirname(__DIR__) . '/src'));
        foreach ($files as $file) {
            if (!$file instanceof \SplFileInfo || $file->getExtension() !== 'php') {
                continue;
            }
            $relative = substr($file->getPathname(), strlen(dirname(__DIR__) . '/src/'), -4);
            $class = 'Raoh\\Notation199x\\' . str_replace('/', '\\', $relative);
            self::assertTrue(class_exists($class) || enum_exists($class), $class);
            foreach ((new \ReflectionClass($class))->getProperties(\ReflectionProperty::IS_STATIC) as $property) {
                $held[] = $class . '::$' . $property->getName();
            }
        }
        sort($held);
        $listed = array_keys(self::STATE_HELD);
        sort($listed);
        self::assertSame($listed, $held);
    }

    public function testTheSourcesAskTheHostNothingAboutUnicode(): void
    {
        $named = [];
        $pcre = [];
        $files = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator(dirname(__DIR__) . '/src'));
        foreach ($files as $file) {
            if (!$file instanceof \SplFileInfo || $file->getExtension() !== 'php') {
                continue;
            }
            $source = file_get_contents($file->getPathname());
            self::assertIsString($source);
            $tokens = array_values(array_filter(
                token_get_all($source),
                static fn ($token): bool => !is_array($token) || !in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true),
            ));
            foreach ($tokens as $at => $token) {
                if (!is_array($token) || !in_array($token[0], [T_STRING, T_NAME_FULLY_QUALIFIED, T_NAME_QUALIFIED], true)) {
                    continue;
                }
                // A name the sources call, construct or reach a member of. A case of the
                // package's own enums, such as TemporalKind::DateTime, is none of those.
                $after = $tokens[$at + 1] ?? null;
                $before = $tokens[$at - 1] ?? null;
                $called = $after === '(' || is_array($after) && $after[0] === T_DOUBLE_COLON
                    || is_array($before) && $before[0] === T_NEW;
                if (!$called) {
                    continue;
                }
                $name = strtolower(ltrim($token[1], '\\'));
                foreach (self::ASKED_NOTHING as $prefix) {
                    if (str_starts_with($name, $prefix)) {
                        $named[] = $file->getFilename() . ': ' . $token[1];
                    }
                }
                if (str_starts_with($name, 'preg_')) {
                    $pcre[$file->getFilename()] = ($pcre[$file->getFilename()] ?? 0) + 1;
                }
            }
        }
        self::assertSame([], $named);
        self::assertSame(self::PCRE_ASKED, $pcre);
    }
}
