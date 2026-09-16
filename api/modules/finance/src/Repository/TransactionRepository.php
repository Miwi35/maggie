<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;

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
        $total = $this->createQueryBuilder('t')
            ->select('SUM(t.amountCents)')
            ->andWhere('t.user = :user')
            ->andWhere('t.category = :category')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('category', $category->getId(), 'ulid')
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }
}
