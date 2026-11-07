<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;

/** @extends ServiceEntityRepository<HealthDay> */
class HealthDayRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, HealthDay::class);
    }

    public function findOneByDate(User $user, \DateTimeImmutable $date): ?HealthDay
    {
        return $this->findOneBy(['user' => $user, 'date' => $date]);
    }

    /** @return list<HealthDay> the days from $from to $to included, the oldest first */
    public function findInRange(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<HealthDay> */
        return $this->createQueryBuilder('d')
            ->andWhere('d.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('d.date >= :from')->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->andWhere('d.date <= :to')->setParameter('to', $to, Types::DATE_IMMUTABLE)
            ->orderBy('d.date', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return list<string> the dates (YYYY-MM-DD) the user has a journal row on, the oldest first */
    public function datesOf(User|string $user): array
    {
        $rows = $this->createQueryBuilder('d')
            ->select('d.date')
            ->andWhere('d.user = :user')->setParameter('user', $user instanceof User ? $user->getId() : $user, 'ulid')
            ->orderBy('d.date', 'ASC')
            ->getQuery()
            ->getArrayResult();

        return array_values(array_map(static fn (array $row): string => $row['date']->format('Y-m-d'), $rows));
    }
}
