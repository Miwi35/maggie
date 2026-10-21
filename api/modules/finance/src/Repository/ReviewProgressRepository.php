<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Enum\ReviewStep;

/** @extends ServiceEntityRepository<ReviewProgress> */
class ReviewProgressRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, ReviewProgress::class);
    }

    /** @param \DateTimeImmutable $month any day of the month */
    public function findOneByUserAndMonth(User $user, \DateTimeImmutable $month): ?ReviewProgress
    {
        return $this->findOneBy(['user' => $user, 'month' => new \DateTimeImmutable($month->format('Y-m-01'), new \DateTimeZone('UTC'))]);
    }

    /** When the owner last closed a review: the last touch of the most recent one that reached `done`. */
    public function findLastCompletedAt(User $user): ?\DateTimeImmutable
    {
        /** @var ReviewProgress|null $progress */
        $progress = $this->createQueryBuilder('p')
            ->andWhere('p.user = :user')
            ->andWhere('p.step = :done')
            ->andWhere('p.updatedAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('done', ReviewStep::Done->value)
            ->orderBy('p.updatedAt', 'DESC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();

        return $progress?->getUpdatedAt();
    }
}
