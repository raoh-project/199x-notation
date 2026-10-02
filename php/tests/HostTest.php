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
