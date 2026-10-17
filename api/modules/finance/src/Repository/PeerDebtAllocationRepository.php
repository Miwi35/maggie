<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Entity\Transaction;

/**
 * @extends ServiceEntityRepository<PeerDebtAllocation>
 */
class PeerDebtAllocationRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, PeerDebtAllocation::class);
    }

    /** @return list<PeerDebtAllocation> the shares this transfer gives back, in the order they were allocated */
    public function findByTransaction(Transaction $transaction): array
    {
        /** @var list<PeerDebtAllocation> $allocations */
        $allocations = $this->createQueryBuilder('a')
            ->innerJoin('a.share', 's')->addSelect('s')
            ->innerJoin('s.peerDebt', 'd')->addSelect('d')
            ->andWhere('a.transaction = :transaction')
            ->setParameter('transaction', $transaction->getId(), 'ulid')
            ->orderBy('a.id', 'ASC')
            ->getQuery()
            ->getResult();

        return $allocations;
    }
}
