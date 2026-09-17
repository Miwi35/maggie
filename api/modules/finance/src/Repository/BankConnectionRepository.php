<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\BankConnection;

/** @extends ServiceEntityRepository<BankConnection> */
class BankConnectionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, BankConnection::class);
    }

    /** @return BankConnection[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['createdAt' => 'DESC']);
    }

    /** The journey a bank is answering about, found by the state we sent it. */
    public function findOneByState(string $state): ?BankConnection
    {
        return $this->findOneBy(['state' => $state]);
    }
}
