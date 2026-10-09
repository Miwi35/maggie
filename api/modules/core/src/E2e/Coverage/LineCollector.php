<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

/**
 * Records which lines run between `start()` and `stop()`. pcov in the stack (PcovLineCollector),
 * a fake in the tests.
 */
interface LineCollector
{
    public function start(): void;

    /**
     * Stops recording and forgets what was recorded.
     *
     * @return array<string, array<int, int>> absolute file => line => hits (> 0 when executed)
     */
    public function stop(): array;
}
