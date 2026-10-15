<?php

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\DBAL\Types\Types;
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
        // The due date is a day: today is in, whatever the hour.
        $today = new \DateTimeImmutable('today');
        $end = $today->modify("+{$days} days");

        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate >= :today')
            ->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('t.dueDate <= :end')
            ->setParameter('today', $today, Types::DATE_IMMUTABLE)
            ->setParameter('end', $end, Types::DATE_IMMUTABLE)
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
        // Overdue from the day after: a task due today is not late yet.
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate < :today')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('today', new \DateTimeImmutable('today'), Types::DATE_IMMUTABLE)
            ->orderBy('t.dueDate', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The tasks not done whose due day is $day or before, the furthest back
     * first: the most overdue leads, then the order Google
     * keeps. A task with no due day is never in.
     *
     * @return Task[]
     */
    public function findOpenDueUntil(User $user, \DateTimeImmutable $day): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate <= :day')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('day', $day, Types::DATE_IMMUTABLE)
            ->orderBy('t.dueDate', 'ASC')
            ->addOrderBy('t.position', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The tasks not done due after $day, the closest first (MAG-408).
     *
     * @return Task[]
     */
    public function findOpenDueAfter(User $user, \DateTimeImmutable $day): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NOT NULL')
            ->andWhere('t.dueDate > :day')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('day', $day, Types::DATE_IMMUTABLE)
            ->orderBy('t.dueDate', 'ASC')
            ->addOrderBy('t.position', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The tasks not done with no due day, in their manual order (MAG-408).
     *
     * @return Task[]
     */
    public function findOpenUndated(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.dueDate IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.position', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * One page of the done tasks, the most recently done first (MAG-408).
     *
     * @return Task[]
     */
    public function findDonePage(User $user, int $page, int $itemsPerPage): array
    {
        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.completedAt', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->setFirstResult(($page - 1) * $itemsPerPage)
            ->setMaxResults($itemsPerPage)
            ->getQuery()
            ->getResult();
    }

    public function countDone(User $user): int
    {
        return (int) $this->createQueryBuilder('t')
            ->select('COUNT(t.id)')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getSingleScalarResult();
    }

    /**
     * The subtasks not done of $parents, in their manual order.
     *
     * @param Task[] $parents
     *
     * @return Task[]
     */
    public function findOpenSubtasksOf(User $user, array $parents): array
    {
        if ([] === $parents) {
            return [];
        }

        return $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.completedAt IS NULL')
            ->andWhere('t.parent IN (:parents)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('parents', array_map(static fn (Task $parent): string => $parent->getId()->toRfc4122(), $parents), ArrayParameterType::STRING)
            ->orderBy('t.position', 'ASC')
            ->addOrderBy('t.id', 'ASC')
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
     * The tasks under $parent — the top-level ones when null — in their manual
     * order: by position, then by creation for the ones that have none.
     *
     * @return Task[]
     */
    public function findSiblings(User $user, ?Task $parent): array
    {
        $qb = $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.position', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (null === $parent) {
            $qb->andWhere('t.parent IS NULL');
        } else {
            $qb->andWhere('t.parent = :parent')->setParameter('parent', $parent->getId(), 'ulid');
        }

        return $qb->getQuery()->getResult();
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
