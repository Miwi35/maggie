<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Enum\BankConnectionStatus;

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

    /** @return User[] the owners the unattended sync has something to pull for */
    public function findOwnersOfActiveConnections(): array
    {
        // A subquery rather than DISTINCT: users carry a json column, which
        // Postgres cannot compare.
        $em = $this->getEntityManager();
        $live = $em->createQueryBuilder()
            ->select('IDENTITY(c.user)')
            ->from(BankConnection::class, 'c')
            ->where('c.status = :status');

        return $em->createQueryBuilder()
            ->select('u')
            ->from(User::class, 'u')
            ->where($em->getExpressionBuilder()->in('u.id', $live->getDQL()))
            ->setParameter('status', BankConnectionStatus::Active)
            ->orderBy('u.email', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Live links whose consent runs out by the horizon — or already has: the
     * stored status stays Active until a sync notices, so the date decides.
     *
     * @return BankConnection[]
     */
    public function findActiveExpiringBy(\DateTimeImmutable $horizon): array
    {
        return $this->createQueryBuilder('c')
            ->where('c.status = :status')
            ->andWhere('c.consentExpiresAt IS NOT NULL')
            ->andWhere('c.consentExpiresAt <= :horizon')
            ->setParameter('status', BankConnectionStatus::Active)
            ->setParameter('horizon', $horizon)
            ->orderBy('c.consentExpiresAt', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
