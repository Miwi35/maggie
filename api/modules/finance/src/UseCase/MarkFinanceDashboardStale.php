<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Lays down « the dashboard is out of date » at once, in the write that
 * changed it, then asks for the rebuild: the read falls back to the live
 * computation until the rebuild is stored, so it never answers old figures.
 *
 * The rebuild is queued, not run here: a bank sync or an import changes the
 * figures dozens of times, and each message after the first finds a fresh row
 * and does nothing.
 */
class MarkFinanceDashboardStale
{
    public function __construct(
        private readonly FinanceDashboardSnapshotRepository $snapshots,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function execute(string $userId): void
    {
        $this->snapshots->markStale($userId);
        $this->bus->dispatch(new RefreshFinanceDashboardCommand($userId));
    }
}
