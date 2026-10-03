<?php

declare(strict_types=1);

/**
 * Times each case Tests\Support\Walks names, as the median of nine batches each of about 50 ms.
 *
 *     php -d opcache.enable_cli=1 bench/walks.php [src]
 *
 * With src, the package is loaded from there instead, so that a copy of another commit's src can
 * be timed on the same cases: a copy of develop's, say, against this checkout's. Each case is
 * asked, before it is timed, whether it took its path, and one that did not is reported and not
 * timed.
 */

use Raoh\Notation199x\Internal\Pattern\Machine;
use Raoh\Notation199x\Tests\Support\Walks;

require dirname(__DIR__) . '/vendor/autoload.php';
// Registered after Composer's, and put before it, so that src is where the package comes from.
$src = $argv[1] ?? null;
if ($src !== null) {
    spl_autoload_register(static function (string $class) use ($src): void {
        $prefix = 'Raoh\\Notation199x\\';
        if (str_starts_with($class, $prefix) && !str_starts_with($class, $prefix . 'Tests\\')) {
            require $src . '/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
        }
    }, true, true);
}
printf("%s\n", dirname((string) (new ReflectionClass(Machine::class))->getFileName(), 3));

$room = Machine::$knownBytes;
foreach (Walks::cases() as $name => $setUp) {
    [$run, $why] = $setUp();
    $missed = $why();
    if ($missed !== null) {
        printf("%-56s did not take its path: %s\n", $name, $missed);
    } else {
        $run();
        $start = hrtime(true);
        $reps = 0;
        do {
            $run();
            $reps++;
        } while (hrtime(true) - $start < 50_000_000 && $reps < 100_000);
        $times = [];
        for ($batch = 0; $batch < 9; $batch++) {
            $start = hrtime(true);
            for ($i = 0; $i < $reps; $i++) {
                $run();
            }
            $times[] = (hrtime(true) - $start) / $reps / 1000;
        }
        sort($times);
        printf("%-56s %12.2f us\n", $name, $times[4]);
    }
    Machine::$knownBytes = $room;
}
