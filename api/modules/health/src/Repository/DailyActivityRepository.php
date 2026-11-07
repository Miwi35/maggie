<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\DailyActivity;

/** @extends ServiceEntityRepository<DailyActivity> */
class DailyActivityRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, DailyActivity::class);
    }

    public function findOneByDate(User $user, \DateTimeImmutable $date): ?DailyActivity
    {
        /* @var DailyActivity|null */
        return $this->createQueryBuilder('a')
            ->andWhere('a.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('a.date = :date')->setParameter('date', $date, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** @return list<DailyActivity> the days from $from to $to included, the oldest first */
    public function findInRange(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<DailyActivity> */
        return $this->createQueryBuilder('a')
            ->andWhere('a.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('a.date >= :from')->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->andWhere('a.date <= :to')->setParameter('to', $to, Types::DATE_IMMUTABLE)
            ->orderBy('a.date', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
