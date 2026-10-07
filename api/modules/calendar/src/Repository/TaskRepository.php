<?php

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Task;
use Maggie\Core\Entity\User;

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
    public function findByDueDateRange(User $user, \DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.dueDate >= :start')
            ->andWhere('t.dueDate <= :end')
            ->setParameter('user', $user->getId(), 'ulid')
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
    public function findPending(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find pending tasks with a due date within the next N days.
     *
     * @return Task[]
     */
    public function findUpcoming(User $user, int $days = 7): array
    {
        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate >= :now')
            ->setParameter('user', $user->getId(), 'ulid')
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
    public function findOverdue(User $user): array
    {
        $now = new \DateTimeImmutable('now');

        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate < :now')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('now', $now)
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find completed tasks, most recently completed first.
     *
     * @return Task[]
     */
    public function findDone(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.completedAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Task[]
     */
    public function findAllForUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['dueDate' => 'ASC']);
    }

    /**
     * Tasks still tracked against a Google list the user no longer syncs with.
     *
     * Switching lists leaves them pointing at identifiers the new list has
     * never heard of: a push would then update or delete someone else's task,
     * and a pull of the new list would never see them to clean them up
     * (MAG-118).
     *
     * @return Task[]
     */
    public function findTrackedOnAnotherGoogleList(User $user, string $taskListId): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.googleTaskId IS NOT NULL')
            ->andWhere('(t.googleTaskListId IS NULL OR t.googleTaskListId != :taskListId)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('taskListId', $taskListId)
            ->getQuery()
            ->getResult();
    }
}
