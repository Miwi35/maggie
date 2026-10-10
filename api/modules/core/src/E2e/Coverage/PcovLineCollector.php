<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

/**
 * pcov, one request at a time: a php-fpm worker serves one request at once, so what it records
 * between start and stop belongs to that request alone.
 *
 * Inert when pcov is not loaded — the extension is only there under E2E_COVERAGE=1
 * (.docker/php/coverage.d/pcov.ini).
 */
final class PcovLineCollector implements LineCollector
{
    public function start(): void
    {
        if (!\function_exists('pcov\start')) {
            return;
        }

        // A request that died before kernel.terminate would otherwise hand its lines to the next.
        \pcov\clear();
        \pcov\start();
    }

    public function stop(): array
    {
        if (!\function_exists('pcov\stop')) {
            return [];
        }

        \pcov\stop();
        $lines = \pcov\collect(\pcov\all);
        \pcov\clear();

        return $lines;
    }
}
