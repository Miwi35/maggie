<?php

namespace Maggie\Calendar\Repository;

use Maggie\Calendar\Entity\Task;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Task>
 */
class TaskRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Task::class);
    }

    /**
     * Find tasks with a due date within a given range.
     *
     * @return Task[]
     */
    public function findByDueDateRange(\DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.dueDate >= :start')
            ->andWhere('t.dueDate <= :end')
            ->setParameter('start', $start)
            ->setParameter('end', $end)
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find tasks that are not done.
     *
     * @return Task[]
     */
    public function findPending(): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.completedAt IS NULL')
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find pending tasks with a due date within the next N days.
     *
     * @return Task[]
     */
    public function findUpcoming(int $days = 7): array
    {
        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        return $this->createQueryBuilder('t')
            ->where('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate >= :now')
            ->andWhere('t.dueDate <= :end')
            ->setParameter('now', $now)
            ->setParameter('end', $end)
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find pending tasks whose due date has passed.
     *
     * @return Task[]
     */
    public function findOverdue(): array
    {
        $now = new \DateTimeImmutable('now');

        return $this->createQueryBuilder('t')
            ->where('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate < :now')
            ->setParameter('now', $now)
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
