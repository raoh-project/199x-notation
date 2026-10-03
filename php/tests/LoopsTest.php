<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Tests;

use PHPUnit\Framework\TestCase;
use Raoh\Notation199x\CaseConversion;
use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Normalization;

/**
 * What a match, a normalization and a case conversion do that grows with the text, the machine or
 * the sets kept is done in loops of their own, where a checkpoint added later asks, and not inside
 * a call to PHP. PHP has no checkpoint yet; this holds the three rules to the shape one is added to.
 *
 * Each rule is the methods its core reaches, followed through the calls the sources make: to a
 * method of the same class, of a class named, of a property's class, or of the one class the file
 * names that has a method of that name. A call this cannot follow fails the test rather than being
 * left out, so a loop cannot be reached out of its sight.
 */
final class LoopsTest extends TestCase
{
    /**
     * Each rule's core, whose doc comment names the loops a checkpoint added later asks in, as
     * name() or Class::name() after "asks in each of them:".
     */
    private const RULES = [
        'match' => [Machine::class, 'matches'],
        'normalization' => [Normalization::class, 'normalizeCore'],
        'case conversion' => [CaseConversion::class, 'map'],
    ];

    /**
     * The loops a rule reaches that a checkpoint has no need to ask in, and why: each goes over no
     * more than a bound that does not grow with the text, or a search logarithmic in what it
     * searches, as Java's Checkpoint allows between two asks.
     */
    private const BOUNDED = [
        'match' => [
            'SymbolSets::holds' => 'a binary search over one set of symbols',
            'Utf8::scalarAt' => 'the continuation bytes of one character, at most three',
        ],
        'normalization' => [
            'Composing::decompose' => 'one character\'s decomposition, as long as the tables make one',
            'Utf8::split' => 'one character\'s mapping, as long as the tables make one',
        ],
        'case conversion' => [
            'CaseConversion::stops' => 'the 256 byte values, once a request',
            'Ranges::has' => 'a binary search over one property\'s ranges',
            'Utf8::countShort' => 'one character\'s mapping, as long as the tables make one',
        ],
    ];

    /**
     * The functions of PHP each rule calls, and "..." where it spreads an array. Each goes over no
     * more than a character, a table's entry or what BOUNDED bounds, or makes or copies what is
     * written once, apart from a run gone past in one call, which RUNS bounds. A sort, array_keys
     * and the like go over all they are handed, and are not on these lists.
     */
    private const CALLS = [
        'match' => ['array_fill', 'array_pop', 'count', 'max', 'ord', 'strlen', 'strspn', 'substr'],
        // array_slice and "..." go over the few marks insertion orders, or one decomposition.
        'normalization' => ['...', 'array_fill', 'array_push', 'array_slice', 'chr', 'count', 'intdiv', 'max', 'ord', 'strlen', 'strspn', 'substr'],
        // strtr maps a run of ASCII strspn has gone past, and is given no more than that run.
        'case conversion' => ['chr', 'intdiv', 'count', 'ord', 'strcspn', 'strlen', 'strspn', 'strtr', 'substr'],
    ];

    /** What goes past a run of the text in one call, and is given the run's length. */
    private const RUNS = ['strspn', 'strcspn'];

    /**
     * @var array<string, array{methods: array<string, array{loops: bool, calls: list<string>, php: list<string>, runs: list<int>}>}>|null
     */
    private static ?array $classes = null;

    /**
     * Each rule's doc comment names the loops it reaches, apart from those BOUNDED says need no
     * ask, and each name is a loop it reaches.
     */
    public function testEachRuleNamesEveryLoopItReaches(): void
    {
        foreach (self::RULES as $rule => [$class, $core]) {
            $short = self::short($class);
            $reached = self::reached($short . '::' . $core);
            $looping = [];
            foreach ($reached as $method) {
                [$owner, $name] = explode('::', $method);
                if (self::classes()[$owner]['methods'][$name]['loops']) {
                    $looping[] = $method;
                }
            }
            $bounded = array_keys(self::BOUNDED[$rule]);
            foreach ($bounded as $method) {
                self::assertContains($method, $looping, "$rule: $method is bounded but not a loop the rule reaches");
            }
            $named = self::named($class, $core);
            $unbounded = array_values(array_diff($looping, $bounded));
            sort($unbounded);
            self::assertSame($unbounded, $named, "$rule: the doc comment of $short::$core");
        }
    }

    /**
     * Each rule calls the functions of PHP its list names and no others, and gives each run a
     * length, the fourth argument, which a checkpoint added later makes one character.
     */
    public function testEachRuleCallsOnlyWhatGoesOverNoMoreThanACharacter(): void
    {
        foreach (self::RULES as $rule => [$class, $core]) {
            $called = [];
            foreach (self::reached(self::short($class) . '::' . $core) as $method) {
                [$owner, $name] = explode('::', $method);
                $body = self::classes()[$owner]['methods'][$name];
                foreach ($body['php'] as $function) {
                    $called[$function] = true;
                }
                foreach ($body['runs'] as $arguments) {
                    self::assertSame(4, $arguments, "$rule: a run gone past in $method");
                }
            }
            $called = array_keys($called);
            sort($called);
            $allowed = self::CALLS[$rule];
            sort($allowed);
            self::assertSame($allowed, $called, $rule);
        }
    }

    /**
     * The methods $from reaches, itself among them, as Class::name.
     *
     * @return list<string>
     */
    private static function reached(string $from): array
    {
        $reached = [$from => true];
        $pending = [$from];
        while ($pending !== []) {
            [$owner, $name] = explode('::', array_pop($pending));
            self::assertArrayHasKey($owner, self::classes(), "$owner is not a class of the package");
            self::assertArrayHasKey($name, self::classes()[$owner]['methods'], "$owner has no method $name");
            foreach (self::classes()[$owner]['methods'][$name]['calls'] as $callee) {
                // A call that could not be followed is held as why, and fails once it is reached.
                self::assertStringNotContainsString(' ', $callee, "$owner::$name: $callee");
                if (!isset($reached[$callee])) {
                    $reached[$callee] = true;
                    $pending[] = $callee;
                }
            }
        }
        $reached = array_keys($reached);
        sort($reached);
        return $reached;
    }

    /**
     * The methods the doc comment of $class::$core names after "asks in each of them:", up to the
     * end of that paragraph, as Class::name.
     *
     * @param class-string $class
     * @return list<string>
     */
    private static function named(string $class, string $core): array
    {
        $doc = (new \ReflectionMethod($class, $core))->getDocComment();
        self::assertIsString($doc);
        $from = strpos($doc, 'asks in each of them:');
        self::assertIsInt($from, "$class::$core names no loops");
        $paragraph = preg_split('/\n\s*\*\s*\n/', substr($doc, $from))[0] ?? '';
        preg_match_all('/\b(?:([A-Z]\w*)::)?([a-z]\w*)\(\)/', $paragraph, $names, PREG_SET_ORDER);
        $named = [];
        foreach ($names as $match) {
            $named[] = ($match[1] !== '' ? $match[1] : self::short($class)) . '::' . $match[2];
        }
        sort($named);
        return $named;
    }

    private static function short(string $class): string
    {
        $at = strrpos($class, '\\');
        return $at === false ? $class : substr($class, $at + 1);
    }

    /**
     * Every class of the package by its short name, with the methods each declares: whether each
     * has a loop, the methods of the package it calls, the functions of PHP it calls, and how many
     * arguments each run it goes past is given.
     *
     * @return array<string, array{methods: array<string, array{loops: bool, calls: list<string>, php: list<string>, runs: list<int>}>}>
     */
    private static function classes(): array
    {
        if (self::$classes !== null) {
            return self::$classes;
        }
        $src = dirname(__DIR__) . '/src/';
        // What each class is read from: its full name and its tokens.
        $read = [];
        $files = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($src));
        foreach ($files as $file) {
            if (!$file instanceof \SplFileInfo || $file->getExtension() !== 'php') {
                continue;
            }
            $relative = substr($file->getPathname(), strlen($src), -4);
            $fqcn = 'Raoh\\Notation199x\\' . str_replace('/', '\\', $relative);
            $short = self::short($fqcn);
            self::assertArrayNotHasKey($short, $read, "two classes are named $short");
            $source = file_get_contents($file->getPathname());
            self::assertIsString($source);
            $tokens = array_values(array_filter(
                token_get_all($source),
                static fn ($token): bool => !is_array($token) || !in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true),
            ));
            $read[$short] = [$fqcn, $tokens];
        }
        // The methods each class declares, and the classes of the package each file names, which
        // a call on a variable is looked up in.
        $declared = [];
        $refs = [];
        foreach ($read as $short => [, $tokens]) {
            $declared[$short] = self::methods($tokens);
            $named = [];
            foreach ($tokens as $token) {
                if (is_array($token) && in_array($token[0], [T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED], true)) {
                    $name = self::short($token[1]);
                    if ($name !== $short && isset($read[$name])) {
                        $named[$name] = true;
                    }
                }
            }
            $refs[$short] = array_keys($named);
        }
        $fqcns = [];
        foreach ($read as $short => [$fqcn]) {
            $fqcns[$short] = $fqcn;
        }
        $classes = [];
        foreach ($read as $short => [, $tokens]) {
            $methods = [];
            foreach ($declared[$short] as $name => [$open, $close]) {
                $methods[$name] = self::body($declared, $refs, $fqcns, $short, $tokens, $open, $close);
            }
            $classes[$short] = ['methods' => $methods];
        }
        return self::$classes = $classes;
    }

    /**
     * Each method a file declares, by name, as where its body opens and closes among $tokens.
     *
     * @param list<array{int, string, int}|string> $tokens
     * @return array<string, array{int, int}>
     */
    private static function methods(array $tokens): array
    {
        $methods = [];
        $count = count($tokens);
        for ($at = 0; $at < $count; $at++) {
            $token = $tokens[$at];
            $next = $tokens[$at + 1] ?? null;
            if (!is_array($token) || $token[0] !== T_FUNCTION || !is_array($next) || $next[0] !== T_STRING) {
                continue;
            }
            $open = $at + 2;
            while ($open < $count && $tokens[$open] !== '{' && $tokens[$open] !== ';') {
                $open++;
            }
            if ($tokens[$open] === ';') {
                continue;
            }
            $depth = 0;
            for ($close = $open; $close < $count; $close++) {
                $t = $tokens[$close];
                if ($t === '{' || is_array($t) && in_array($t[0], [T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true)) {
                    $depth++;
                } elseif ($t === '}') {
                    $depth--;
                    if ($depth === 0) {
                        break;
                    }
                }
            }
            $methods[$next[1]] = [$open, $close];
            $at = $close;
        }
        return $methods;
    }

    /**
     * What the body of $short's method between $open and $close does, as classes() says.
     *
     * @param array<string, array<string, array{int, int}>> $declared the methods each class declares
     * @param array<string, list<string>> $refs the classes of the package each file names
     * @param array<string, string> $fqcns
     * @param list<array{int, string, int}|string> $tokens
     * @return array{loops: bool, calls: list<string>, php: list<string>, runs: list<int>}
     */
    private static function body(array $declared, array $refs, array $fqcns, string $short, array $tokens, int $open, int $close): array
    {
        $loops = false;
        $calls = [];
        $php = [];
        $runs = [];
        for ($at = $open; $at < $close; $at++) {
            $token = $tokens[$at];
            if (is_array($token) && in_array($token[0], [T_FOR, T_FOREACH, T_WHILE, T_DO], true)) {
                $loops = true;
            }
            if (is_array($token) && $token[0] === T_ELLIPSIS) {
                $php['...'] = true;
            }
            if ($tokens[$at + 1] !== '(' || !is_array($token)) {
                if (is_array($token) && $token[0] === T_NEW) {
                    $name = self::short(is_array($tokens[$at + 1]) ? $tokens[$at + 1][1] : '');
                    if (isset($declared[$name]['__construct'])) {
                        $calls[$name . '::__construct'] = true;
                    }
                }
                continue;
            }
            if (!in_array($token[0], [T_STRING, T_NAME_FULLY_QUALIFIED], true)) {
                continue;
            }
            $name = $token[1];
            $before = $tokens[$at - 1];
            $beforeKind = is_array($before) ? $before[0] : $before;
            if ($beforeKind === T_FUNCTION || $beforeKind === T_NEW) {
                continue;
            }
            if ($beforeKind === T_DOUBLE_COLON) {
                $on = self::short(is_array($tokens[$at - 2]) ? $tokens[$at - 2][1] : '');
                $on = in_array($on, ['self', 'static'], true) ? $short : $on;
                if (isset($declared[$on])) {
                    $calls[$on . '::' . $name] = true;
                }
                continue;
            }
            if ($beforeKind === T_OBJECT_OPERATOR || $beforeKind === T_NULLSAFE_OBJECT_OPERATOR) {
                $on = self::receiver($declared, $refs, $fqcns, $short, $tokens, $at - 2, $name);
                $calls[str_contains($on, ' ') ? $on : $on . '::' . $name] = true;
                continue;
            }
            $function = strtolower(ltrim($name, '\\'));
            $php[$function] = true;
            if (in_array($function, self::RUNS, true)) {
                $runs[] = self::arguments($tokens, $at + 1);
            }
        }
        return ['loops' => $loops, 'calls' => array_keys($calls), 'php' => array_keys($php), 'runs' => $runs];
    }

    /**
     * The class of what a method is called on, ending at $at: $this, a property of $this whose
     * type is a class of the package, or a variable, taken as the one class the file names that
     * has a method $name. Where it cannot tell, it answers why, which holds a space.
     *
     * @param array<string, array<string, array{int, int}>> $declared
     * @param array<string, list<string>> $refs
     * @param array<string, string> $fqcns
     * @param list<array{int, string, int}|string> $tokens
     */
    private static function receiver(array $declared, array $refs, array $fqcns, string $short, array $tokens, int $at, string $name): string
    {
        $token = $tokens[$at];
        if (is_array($token) && $token[0] === T_VARIABLE && $token[1] === '$this') {
            return $short;
        }
        $before = $tokens[$at - 1];
        $self = $tokens[$at - 2];
        if (is_array($token) && $token[0] === T_STRING && is_array($before) && $before[0] === T_OBJECT_OPERATOR
            && is_array($self) && $self[1] === '$this') {
            if (!class_exists($fqcns[$short]) || !property_exists($fqcns[$short], $token[1])) {
                return "$short has no property \${$token[1]}";
            }
            $type = (new \ReflectionProperty($fqcns[$short], $token[1]))->getType();
            if (!$type instanceof \ReflectionNamedType || !isset($declared[self::short($type->getName())])) {
                return "\${$token[1]} is of no class of the package";
            }
            return self::short($type->getName());
        }
        if (is_array($token) && $token[0] === T_VARIABLE) {
            $candidates = [];
            foreach ($refs[$short] as $ref) {
                if (isset($declared[$ref][$name])) {
                    $candidates[] = $ref;
                }
            }
            return count($candidates) === 1 ? $candidates[0] : "cannot tell what {$token[1]}->$name() calls";
        }
        return "cannot tell what ->$name() is called on";
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
