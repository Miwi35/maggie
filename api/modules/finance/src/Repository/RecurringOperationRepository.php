<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;

/**
 * The operations, series and others. What reads a period, an anchor or an
 * account asks for the series; what files lines by priority asks for the
 * operations without recurrence.
 *
 * @extends ServiceEntityRepository<RecurringOperation>
 */
class RecurringOperationRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, RecurringOperation::class);
    }

    /**
     * Every operation of a user: the series first, by label, then the others
     * in the order they get their say.
     *
     * @return RecurringOperation[]
     */
    public function findByUser(User $user): array
    {
        return [...$this->findSeriesByUser($user), ...$this->findWithoutRecurrenceByUser($user)];
    }

    /**
     * A user's series, in a stable order: by label, then oldest first.
     *
     * @return RecurringOperation[]
     */
    public function findSeriesByUser(User $user): array
    {
        return $this->createQueryBuilder('o')
            ->andWhere('o.user = :user')
            ->andWhere('o.period IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('o.label', 'ASC')
            ->addOrderBy('o.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * A user's operations without recurrence, in the order they get their
     * say: highest priority first, then oldest first (ULIDs sort by creation
     * time).
     *
     * @return RecurringOperation[]
     */
    public function findWithoutRecurrenceByUser(User $user, bool $activeOnly = false): array
    {
        $qb = $this->createQueryBuilder('o')
            ->andWhere('o.user = :user')
            ->andWhere('o.period IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('o.priority', 'DESC')
            ->addOrderBy('o.id', 'ASC');

        if ($activeOnly) {
            $qb->andWhere('o.isActive = true');
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The active operations without recurrence, in the order they get their say.
     *
     * @return RecurringOperation[]
     */
    public function findActiveWithoutRecurrenceForUser(User $user): array
    {
        return $this->findWithoutRecurrenceByUser($user, true);
    }
}
