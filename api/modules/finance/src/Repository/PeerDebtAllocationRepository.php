<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Finance\Entity\PeerDebt;
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

    /**
     * Every transfer that gave back a share of these debts, most recent first
     * (MAG-455): the history of a créance.
     *
     * @param list<PeerDebt> $debts
     *
     * @return list<PeerDebtAllocation>
     */
    public function findByPeerDebts(array $debts): array
    {
        if ([] === $debts) {
            return [];
        }

        /** @var list<PeerDebtAllocation> $allocations */
        $allocations = $this->createQueryBuilder('a')
            ->innerJoin('a.share', 's')->addSelect('s')
            ->innerJoin('s.contact', 'c')->addSelect('c')
            ->innerJoin('a.transaction', 't')->addSelect('t')
            ->andWhere('s.peerDebt IN (:debts)')
            ->setParameter('debts', array_map(static fn (PeerDebt $debt): string => $debt->getId()->toRfc4122(), $debts), ArrayParameterType::STRING)
            ->orderBy('t.bookedAt', 'DESC')
            ->addOrderBy('a.id', 'DESC')
            ->getQuery()
            ->getResult();

        return $allocations;
    }
}
