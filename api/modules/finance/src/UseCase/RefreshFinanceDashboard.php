<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Maggie\Finance\Service\FinanceReviewAppointment;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Rebuilds the Finance Dashboard read model of a user for the month they are
 * in (MAG-545): the one place the dashboard is computed for storage, so the
 * read never computes and never writes.
 *
 * A row already up to date is left alone — the queue may hold many rebuilds
 * for one burst of writes, and only the first has work to do. A write that
 * lands while the figures are computed leaves the row stale and the rebuild
 * asks for another one.
 */
class RefreshFinanceDashboard
{
    public function __construct(
        private readonly UserRepository $users,
        private readonly FinanceDashboardSnapshotRepository $snapshots,
        private readonly GetFinanceDashboard $getFinanceDashboard,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly RecordCushionCompletion $recordCushionCompletion,
        private readonly FinanceReviewAppointment $reviewAppointment,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function execute(string $userId): void
    {
        $user = $this->users->find($userId);
        if (null === $user) {
            return;
        }

        // Writes first: it moves the version, and the rebuild below must not mistake it for a race.
        $this->recordCushionCompletion->execute($user);

        $state = $this->snapshots->stateOf($userId);
        if (null === $state || !$state['stale']) {
            return;
        }

        $snapshot = $this->snapshots->findOneByUser($user);
        if (null === $snapshot) {
            return;
        }
        $this->em->refresh($snapshot);

        // Before computing: a rebuild that straddles midnight keeps the older day and is outdated sooner, never later.
        $serverToday = new \DateTimeImmutable('today');
        $today = $this->getFinanceScores->resolveDate($user, null);
        $year = (int) $today->format('Y');
        $month = (int) $today->format('n');

        $payload = json_encode(
            $this->getFinanceDashboard->execute($user, $year, $month),
            JsonResponse::DEFAULT_ENCODING_OPTIONS | \JSON_THROW_ON_ERROR,
        );

        $stale = $this->em->wrapInTransaction(function () use ($userId, $state, $snapshot, $year, $month, $today, $serverToday, $payload): bool {
            // Locked until stored: a write marking the row stale now waits, and wins.
            $stale = $this->snapshots->lockVersion($userId) !== $state['version'];
            $snapshot->store($year, $month, $today, $serverToday, $payload, $stale);

            return $stale;
        });

        if ($stale) {
            $this->bus->dispatch(new RefreshFinanceDashboardCommand($userId));
        }

        // Last: a first-read race on the agenda leaves the entity manager closed (see the service).
        $this->reviewAppointment->ensure($user);
    }
}
