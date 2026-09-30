<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Enum\TransactionStatus;

/** @extends ServiceEntityRepository<Transaction> */
class TransactionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Transaction::class);
    }

    /** @return Transaction[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['bookedAt' => 'DESC']);
    }

    /** @return Transaction[] */
    public function findByAccount(Account $account): array
    {
        return $this->findBy(['account' => $account], ['bookedAt' => 'DESC']);
    }

    /**
     * Total spent on a category over a half-open period, as positive cents.
     * Only debits (negative amounts) count against a budget.
     */
    public function sumSpentForCategoryBetween(User $user, Category $category, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $byStatus = $this->sumByStatusForCategoryBetween($user, $category, $from, $until);

        return $byStatus[TransactionStatus::Spent->value];
    }

    /**
     * Debits on a category over a half-open period, split by status, as
     * positive cents. Every status is present, zero when nothing matched.
     *
     * @return array<string, int>
     */
    public function sumByStatusForCategoryBetween(User $user, Category $category, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('t.status AS status, SUM(t.amountCents) AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.category = :category')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('category', $category->getId(), 'ulid')
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('t.status')
            ->getQuery()
            ->getResult();

        $totals = [];
        foreach (TransactionStatus::cases() as $status) {
            $totals[$status->value] = 0;
        }

        foreach ($rows as $row) {
            $status = $row['status'] instanceof TransactionStatus ? $row['status']->value : (string) $row['status'];
            $totals[$status] = abs((int) $row['total']);
        }

        return $totals;
    }

    /**
     * Transactions still up for grabs by the rule engine: no category yet, and
     * not deliberately left uncategorized by hand.
     *
     * @return Transaction[]
     */
    public function findUncategorizedForUser(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.category IS NULL')
            ->andWhere('t.categorySource != :manual')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('manual', CategorySource::Manual->value)
            ->orderBy('t.bookedAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Everything that went out over a half-open period, as positive cents:
     * spent and committed debits, every category together.
     */
    public function sumConsumedBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $total = $this->createQueryBuilder('t')
            ->select('SUM(t.amountCents)')
            ->andWhere('t.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }

    /**
     * Debits of a period that are up for review: everything outside the
     * mandatory categories, uncategorized spends included — those are
     * precisely the ones worth a second look. Biggest first.
     *
     * @return Transaction[]
     */
    public function findReviewableBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->leftJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->andWhere('c.id IS NULL OR c.obligation != :mandatory')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->setParameter('mandatory', ObligationFlag::Mandatory->value)
            ->orderBy('t.amountCents', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Money in and money out, month by month, over a half-open period.
     * Only consumed movements count; both figures come back positive.
     *
     * Grouping happens in PHP: DQL has no portable way to take the year-month
     * out of a date, and a window of a year of movements is small.
     *
     * @return array<string, array{incomeCents: int, expenseCents: int}> keyed by "Y-m"
     */
    public function sumMonthlyFlowsBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        /** @var array<int, array{bookedAt: \DateTimeImmutable, amountCents: int}> $rows */
        $rows = $this->createQueryBuilder('t')
            ->select('t.bookedAt AS bookedAt, t.amountCents AS amountCents')
            ->andWhere('t.user = :user')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getResult();

        $flows = [];
        foreach ($rows as $row) {
            $month = $row['bookedAt']->format('Y-m');
            $flows[$month] ??= ['incomeCents' => 0, 'expenseCents' => 0];

            if ($row['amountCents'] >= 0) {
                $flows[$month]['incomeCents'] += $row['amountCents'];
            } else {
                $flows[$month]['expenseCents'] += abs($row['amountCents']);
            }
        }

        return $flows;
    }

    /**
     * What each category cost over a half-open period, as positive cents,
     * biggest first. Uncategorized spending comes back under a null id.
     *
     * @return array<int, array{categoryId: ?string, categoryName: ?string, spentCents: int}>
     */
    public function sumSpendingByCategoryBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.category) AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS total')
            ->leftJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('categoryId')
            ->addGroupBy('c.name')
            ->getQuery()
            ->getResult();

        $spending = array_map(static fn (array $row) => [
            'categoryId' => null === $row['categoryId'] ? null : (string) $row['categoryId'],
            'categoryName' => $row['categoryName'],
            'spentCents' => abs((int) $row['total']),
        ], $rows);

        usort($spending, static fn (array $a, array $b) => $b['spentCents'] <=> $a['spentCents']);

        return $spending;
    }

    /**
     * How many movements already recorded on this account look exactly like
     * this one. Counting matters: two identical coffees on the same day are
     * two real movements, not a duplicate — only the count above what is
     * already stored should be imported.
     */
    public function countMatching(Account $account, \DateTimeImmutable $bookedAt, int $amountCents, string $label): int
    {
        return (int) $this->createQueryBuilder('t')
            ->select('COUNT(t.id)')
            ->andWhere('t.account = :account')
            ->andWhere('t.bookedAt = :bookedAt')
            ->andWhere('t.amountCents = :amountCents')
            ->andWhere('LOWER(t.label) = :label')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('bookedAt', $bookedAt)
            ->setParameter('amountCents', $amountCents)
            ->setParameter('label', mb_strtolower(trim($label)))
            ->getQuery()
            ->getSingleScalarResult();
    }
}
