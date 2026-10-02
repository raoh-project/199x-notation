<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\Internal\Pattern\Machine;

/**
 * What a match, a normalization and a case conversion do that grows with the text, the machine or
 * the sets kept is done in loops of their own, where a checkpoint added later asks, and not inside
 * a call to PHP. PHP has no checkpoint yet; these hold the loops to the shape one is added to.
 */
final class LoopsTest extends TestCase
{
    /**
     * The functions of PHP each file of those rules calls. Each goes over no more than a character
     * or a table's entry, or makes or copies what is written once, apart from a run gone past in
     * one call, which RUNS bounds. A sort, array_keys and the like go over all they are handed,
     * and a file that calls one is not on this list until that is a loop of its own.
     */
    private const CALLS = [
        'Internal/Pattern/Machine.php' => ['array_fill', 'array_pop', 'count', 'max', 'ord', 'strlen', 'strspn', 'substr'],
        // array_slice and array_push go over the few marks insertion orders, or one decomposition.
        'Internal/Composing.php' => ['array_push', 'array_slice', 'count', 'intdiv', 'ord', 'strlen'],
        'Normalization.php' => ['ord', 'strlen', 'strspn', 'substr'],
        'CaseConversion.php' => ['chr', 'ord', 'strcspn', 'strlen', 'substr'],
    ];

    /** What goes past a run of the text in one call, and is given the run's length. */
    private const RUNS = ['strspn', 'strcspn'];

    /** Machine's methods that build it, once for a pattern and not as a subject is read. */
    private const BUILDING = ['build', 'rows'];

    /**
     * Each file calls the functions of PHP its list names and no others, and gives each run a
     * length, the fourth argument, which a checkpoint added later makes one character.
     */
    public function testTheRulesCallOnlyWhatGoesOverNoMoreThanACharacter(): void
    {
        foreach (self::CALLS as $file => $allowed) {
            $tokens = self::tokens($file);
            $called = [];
            foreach ($tokens as $at => $token) {
                if (!self::isCallOfPhp($tokens, $at)) {
                    continue;
                }
                $name = strtolower(self::text($token));
                $called[$name] = true;
                if (in_array($name, self::RUNS, true)) {
                    self::assertSame(4, self::arguments($tokens, $at + 1), $file . ': ' . $name);
                }
            }
            $called = array_keys($called);
            sort($called);
            self::assertSame($allowed, $called, $file);
        }
    }

    /**
     * The doc comment of Machine::matches names every method of Machine with a loop in it, and
     * each method it names has one, apart from building the machine.
     */
    public function testTheWalkNamesEveryLoopItHas(): void
    {
        $doc = (new \ReflectionMethod(Machine::class, 'matches'))->getDocComment();
        self::assertIsString($doc);
        $from = strpos($doc, 'asks in each of them:');
        self::assertIsInt($from);
        $list = substr($doc, $from);
        $methods = [];
        foreach ((new \ReflectionClass(Machine::class))->getMethods() as $method) {
            $methods[] = $method->getName();
        }
        preg_match_all('/\b[a-z][A-Za-z]*\b/', $list, $words);
        $named = array_values(array_intersect($methods, $words[0]));
        sort($named);

        $tokens = self::tokens('Internal/Pattern/Machine.php');
        $looping = [];
        $method = null;
        foreach ($tokens as $at => $token) {
            if (is_array($token) && $token[0] === T_FUNCTION) {
                $method = self::text($tokens[$at + 1]);
                continue;
            }
            if ($method !== null && !in_array($method, self::BUILDING, true)
                && is_array($token) && in_array($token[0], [T_FOR, T_FOREACH, T_WHILE, T_DO], true)) {
                $looping[$method] = true;
            }
        }
        $looping = array_keys($looping);
        sort($looping);
        self::assertSame($looping, $named);
    }

    /**
     * The tokens of a source file, without whitespace and comments.
     *
     * @return list<array{int, string, int}|string>
     */
    private static function tokens(string $file): array
    {
        $source = file_get_contents(dirname(__DIR__) . '/src/' . $file);
        self::assertIsString($source);
        return array_values(array_filter(
            token_get_all($source),
            static fn ($token): bool => !is_array($token) || !in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true),
        ));
    }

    /**
     * @param array{int, string, int}|string $token
     */
    private static function text(array|string $token): string
    {
        return is_array($token) ? $token[1] : $token;
    }

    /**
     * Whether the token at $at names a function of PHP that is called there: a bare name before an
     * opening parenthesis, and not a method, a declaration or a construction.
     *
     * @param list<array{int, string, int}|string> $tokens
     */
    private static function isCallOfPhp(array $tokens, int $at): bool
    {
        $token = $tokens[$at];
        if (!is_array($token) || !in_array($token[0], [T_STRING, T_NAME_FULLY_QUALIFIED], true)) {
            return false;
        }
        if (($tokens[$at + 1] ?? null) !== '(') {
            return false;
        }
        $before = $tokens[$at - 1] ?? null;
        return !is_array($before)
            || !in_array($before[0], [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW], true);
    }

    /**
     * How many arguments the call whose opening parenthesis is at $open is given.
     *
     * @param list<array{int, string, int}|string> $tokens
     */
    private static function arguments(array $tokens, int $open): int
    {
        $depth = 0;
        $commas = 0;
        for ($at = $open; $at < count($tokens); $at++) {
            $token = $tokens[$at];
            if (in_array($token, ['(', '[', '{'], true)) {
                $depth++;
            } elseif (in_array($token, [')', ']', '}'], true)) {
                $depth--;
                if ($depth === 0) {
                    return $commas + 1;
                }
            } elseif ($token === ',' && $depth === 1) {
                $commas++;
            }
        }
        self::fail('a call that does not close');
    }
}
