<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Meal> */
class MealRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Meal::class);
    }

    /** @return Meal[] */
    public function findByDateRange(\DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        return $this->createQueryBuilder('m')
            ->where('m.startAt >= :from')
            ->andWhere('m.startAt <= :to')
            ->setParameter('from', $from)
            ->setParameter('to', $to)
            ->orderBy('m.startAt', 'ASC')
            ->addOrderBy('m.slot', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** Meals belong to an agenda, which belongs to a user.
     *
     * @return Meal[]
     */
    public function findByDateRangeForUser(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        return $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('m.startAt >= :from')
            ->andWhere('m.startAt <= :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from)
            ->setParameter('to', $to)
            ->orderBy('m.startAt', 'ASC')
            ->addOrderBy('m.slot', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Meal[] */
    public function findByWeek(\DateTimeImmutable $weekStart): array
    {
        $weekEnd = $weekStart->modify('+6 days 23:59:59');

        return $this->findByDateRange($weekStart, $weekEnd);
    }
}
