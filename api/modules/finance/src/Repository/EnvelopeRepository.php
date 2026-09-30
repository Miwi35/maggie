<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;

/** @extends ServiceEntityRepository<Envelope> */
class EnvelopeRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Envelope::class);
    }

    /** @return Envelope[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['year' => 'DESC', 'month' => 'DESC']);
    }

    /** The envelope already budgeting a category for a given period, if any. */
    public function findOneForPeriod(Category $category, BudgetMode $mode, int $year, ?int $month): ?Envelope
    {
        return $this->findOneBy([
            'category' => $category,
            'mode' => $mode,
            'year' => $year,
            'month' => BudgetMode::Monthly === $mode ? $month : null,
        ]);
    }

    /**
     * Envelopes belonging to exactly this period: the monthly ones of that
     * month, or — when no month is given — the annual ones of that year.
     *
     * @return Envelope[]
     */
    public function findForPeriod(User $user, int $year, ?int $month): array
    {
        return $this->findBy([
            'user' => $user,
            'year' => $year,
            'mode' => null === $month ? BudgetMode::Annual : BudgetMode::Monthly,
            'month' => $month,
        ], ['id' => 'ASC']);
    }

    /**
     * Envelopes covering a period: the monthly ones for that month plus the
     * annual ones for that year. A null month keeps every envelope of the year.
     *
     * @return Envelope[]
     */
    public function findCoveringPeriod(User $user, int $year, ?int $month): array
    {
        $qb = $this->createQueryBuilder('e')
            ->andWhere('e.user = :user')
            ->andWhere('e.year = :year')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('year', $year)
            ->orderBy('e.mode', 'ASC')
            ->addOrderBy('e.month', 'ASC');

        if (null !== $month) {
            $qb->andWhere('e.month = :month OR e.month IS NULL')
                ->setParameter('month', $month);
        }

        return $qb->getQuery()->getResult();
    }
}
