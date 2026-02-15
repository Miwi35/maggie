<?php

declare(strict_types=1);

namespace Maggie\Proaction\Repository;

use Maggie\Proaction\Entity\Proaction;
use Maggie\Proaction\Entity\ProactionStatus;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Proaction>
 */
class ProactionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Proaction::class);
    }

    /**
     * @return Proaction[]
     */
    public function findDueProactions(): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.scheduledAt <= :now')
            ->andWhere('p.status = :status')
            ->setParameter('now', new \DateTimeImmutable())
            ->setParameter('status', ProactionStatus::Pending)
            ->orderBy('p.scheduledAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Proaction[]
     */
    public function findByStatus(ProactionStatus $status, int $limit = 50): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.status = :status')
            ->setParameter('status', $status)
            ->orderBy('p.scheduledAt', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Proaction[]
     */
    public function findRecent(int $limit = 50): array
    {
        return $this->createQueryBuilder('p')
            ->orderBy('p.scheduledAt', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }
}
