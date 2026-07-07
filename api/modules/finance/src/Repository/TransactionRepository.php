<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
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
}
