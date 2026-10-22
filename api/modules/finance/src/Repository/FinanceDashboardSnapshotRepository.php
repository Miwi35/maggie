<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Connection;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\FinanceDashboardSnapshot;
use Symfony\Component\Uid\Ulid;

/**
 * The reads and the invalidation go through SQL on purpose: the dashboard
 * answers from one statement, and a write that only says « stale » must not
 * load the entity, touch the unit of work or publish anything.
 *
 * @extends ServiceEntityRepository<FinanceDashboardSnapshot>
 */
class FinanceDashboardSnapshotRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, FinanceDashboardSnapshot::class);
    }

    public function findOneByUser(User $user): ?FinanceDashboardSnapshot
    {
        return $this->findOneBy(['user' => $user]);
    }

    /**
     * The body of the dashboard of this month, when the read model holds it
     * and nothing has changed since: the user's day and the server's day are the
     * ones it was computed on, and no write marked it stale. One statement,
     * nothing written.
     */
    public function fetchFreshPayload(User $user, int $year, int $month, \DateTimeImmutable $today, \DateTimeImmutable $serverToday): ?string
    {
        $payload = $this->connection()->fetchOne(
            'SELECT payload FROM finance_dashboard_snapshot
             WHERE user_id = :user AND NOT stale AND year = :year AND month = :month AND computed_on = :today
               AND computed_server_on = :serverToday',
            [
                'user' => $this->uuid((string) $user->getId()),
                'year' => $year,
                'month' => $month,
                'today' => $today->format('Y-m-d'),
                'serverToday' => $serverToday->format('Y-m-d'),
            ],
        );

        return \is_string($payload) ? $payload : null;
    }

    /**
     * Says that something the dashboard is made of has changed. The row is laid
     * down the first time, empty; the version moves on every call, so a refresh
     * that started before this write learns it is not up to date.
     */
    public function markStale(string $userId): void
    {
        $this->connection()->executeStatement(
            'INSERT INTO finance_dashboard_snapshot (id, user_id, stale, version)
             VALUES (:id, :user, TRUE, 1)
             ON CONFLICT (user_id) DO UPDATE
             SET stale = TRUE, version = finance_dashboard_snapshot.version + 1',
            ['id' => (new Ulid())->toRfc4122(), 'user' => $this->uuid($userId)],
        );
    }

    /** @return array{version: int, stale: bool}|null null when the user has no row yet */
    public function stateOf(string $userId): ?array
    {
        $row = $this->connection()->fetchAssociative(
            'SELECT version, stale FROM finance_dashboard_snapshot WHERE user_id = :user',
            ['user' => $this->uuid($userId)],
        );

        return false === $row ? null : ['version' => (int) $row['version'], 'stale' => (bool) $row['stale']];
    }

    /** The version, with the row locked until the transaction ends: a write marking it stale waits for the refresh to be stored. */
    public function lockVersion(string $userId): ?int
    {
        $version = $this->connection()->fetchOne(
            'SELECT version FROM finance_dashboard_snapshot WHERE user_id = :user FOR UPDATE',
            ['user' => $this->uuid($userId)],
        );

        return false === $version ? null : (int) $version;
    }

    /**
     * Who has finances, and the days their dashboard was computed on: the
     * user's and the server's (null: never, marked stale before its first
     * computation, or stored before the server's day was kept).
     *
     * @return array<string, array{computedOn: string|null, computedServerOn: string|null}> the days as Y-m-d, by user id
     */
    public function computedDaysOfUsersWithAccounts(): array
    {
        $rows = $this->connection()->fetchAllAssociative(
            'SELECT DISTINCT a.user_id AS user_id, s.computed_on AS computed_on, s.computed_server_on AS computed_server_on
             FROM account a
             LEFT JOIN finance_dashboard_snapshot s ON s.user_id = a.user_id',
        );

        $days = [];
        foreach ($rows as $row) {
            $days[Ulid::fromString($row['user_id'])->toBase32()] = ['computedOn' => $row['computed_on'], 'computedServerOn' => $row['computed_server_on']];
        }

        return $days;
    }

    private function connection(): Connection
    {
        return $this->getEntityManager()->getConnection();
    }

    private function uuid(string $userId): string
    {
        return Ulid::fromString($userId)->toRfc4122();
    }
}
