<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;

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
}
