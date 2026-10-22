<?php

namespace App\Tests\Support;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;
use Symfony\Component\Uid\Ulid;

/**
 * Plays the worker for the Finance Dashboard read model: in the test
 * environment the async transport only keeps what was sent, and an HTTP request
 * empties it, so the rebuilds are found where the writes left them — the rows
 * marked stale.
 */
trait FinanceDashboardRefreshTrait
{
    /** Rebuilds every stale dashboard, as the worker would, until none is left. */
    protected function drainDashboardRefreshes(): void
    {
        $connection = self::getContainer()->get(EntityManagerInterface::class)->getConnection();

        for ($round = 0; $round < 5; ++$round) {
            $userIds = $connection->fetchFirstColumn('SELECT user_id FROM finance_dashboard_snapshot WHERE stale');
            if ([] === $userIds) {
                return;
            }

            // Through the bus, as received from the transport: the projection middleware publishes what the rebuild stored.
            foreach ($userIds as $userId) {
                self::getContainer()->get(MessageBusInterface::class)->dispatch(
                    new Envelope(new RefreshFinanceDashboardCommand(Ulid::fromString($userId)->toBase32()), [new ReceivedStamp('async')]),
                );
            }
        }
    }
}
