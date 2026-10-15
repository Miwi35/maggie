<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<DayPlan> */
class DayPlanRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, DayPlan::class);
    }

    public function findOneByUserAndDate(User $user, \DateTimeImmutable $date): ?DayPlan
    {
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('p.date = :date')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('date', $date, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The settled days before $day, the oldest first: what was planned and is
     * past. A proposal the owner never took is not a plan.
     *
     * @return list<DayPlan>
     */
    public function findSettledBefore(User $user, \DateTimeImmutable $day): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('p.date < :day')
            ->andWhere('p.confirmedAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('day', $day, Types::DATE_IMMUTABLE)
            ->orderBy('p.date', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The days from $day on whose slots hold this task.
     *
     * @return list<DayPlan>
     */
    public function findFromHoldingTask(User $user, \DateTimeImmutable $day, string $taskId): array
    {
        $plans = $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('p.date >= :day')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('day', $day, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getResult();

        return array_values(array_filter($plans, static fn (DayPlan $plan): bool => \in_array($taskId, $plan->getTaskIds(), true)));
    }
}
