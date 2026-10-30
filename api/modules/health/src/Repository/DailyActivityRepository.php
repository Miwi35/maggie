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
}
