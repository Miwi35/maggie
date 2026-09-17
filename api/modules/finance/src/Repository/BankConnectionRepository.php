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

    /**
     * The link already held for this bank, whatever state it is in — a stale
     * pending journey, or a consent that ran out. Reconnecting reuses it.
     */
    public function findOneByUserAndBank(User $user, string $bankName, string $country): ?BankConnection
    {
        return $this->findOneBy([
            'user' => $user,
            'bankName' => $bankName,
            'country' => strtoupper($country),
        ], ['createdAt' => 'DESC']);
    }

    /** The journey a bank is answering about, found by the state we sent it. */
    public function findOneByState(string $state): ?BankConnection
    {
        return $this->findOneBy(['state' => $state]);
    }
}
