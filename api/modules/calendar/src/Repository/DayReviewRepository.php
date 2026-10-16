<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\DayReview;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<DayReview> */
class DayReviewRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, DayReview::class);
    }

    public function findOneByUserAndDate(User $user, \DateTimeImmutable $date): ?DayReview
    {
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->andWhere('r.date = :date')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('date', $date, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
