<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;

/** @extends ServiceEntityRepository<PeerDebt> */
class PeerDebtRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, PeerDebt::class);
    }

    public function findOneByOriginTransaction(Transaction $transaction): ?PeerDebt
    {
        return $this->findOneBy(['originTransaction' => $transaction]);
    }

    /** @return PeerDebt[] newest first */
    public function findByUser(User $user, ?Contact $contact = null): array
    {
        $qb = $this->createQueryBuilder('d')
            ->andWhere('d.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('d.createdAt', 'DESC')
            ->addOrderBy('d.id', 'DESC');

        if (null !== $contact) {
            $qb->innerJoin('d.shares', 's')
                ->andWhere('s.contact = :contact')
                ->setParameter('contact', $contact->getId(), 'ulid');
        }

        return $qb->getQuery()->getResult();
    }

    /** What is left to give back in one direction, all contacts together, in cents. */
    public function sumRemaining(User $user, PeerDebtDirection $direction): int
    {
        return (int) $this->getEntityManager()->createQueryBuilder()
            ->select('COALESCE(SUM(CASE WHEN s.amountCents > s.repaidCents THEN s.amountCents - s.repaidCents ELSE 0 END), 0)')
            ->from(PeerDebt::class, 'd')
            ->innerJoin('d.shares', 's')
            ->andWhere('d.user = :user')
            ->andWhere('d.direction = :direction')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('direction', $direction->value)
            ->getQuery()
            ->getSingleScalarResult();
    }

    public function contactHasShares(Contact $contact): bool
    {
        return null !== $this->getEntityManager()->createQueryBuilder()
            ->select('s.id')
            ->from(PeerDebtShare::class, 's')
            ->andWhere('s.contact = :contact')
            ->setParameter('contact', $contact->getId(), 'ulid')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
