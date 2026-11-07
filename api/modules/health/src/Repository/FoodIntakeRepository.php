<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\FoodIntake;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<FoodIntake> */
class FoodIntakeRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, FoodIntake::class);
    }

    /**
     * The intakes of the local days from $from to $to, both included, in the
     * order they were eaten.
     *
     * @return list<FoodIntake>
     */
    public function findInRange(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<FoodIntake> */
        return $this->createQueryBuilder('f')
            ->andWhere('f.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('f.date >= :from')->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->andWhere('f.date <= :to')->setParameter('to', $to, Types::DATE_IMMUTABLE)
            ->orderBy('f.eatenAt', 'ASC')
            ->addOrderBy('f.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** The user's intake, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $foodIntakeId): ?FoodIntake
    {
        if (!Ulid::isValid($foodIntakeId)) {
            return null;
        }

        return $this->findOneBy(['id' => $foodIntakeId, 'user' => $user]);
    }

    /** The intake of a meal: there is at most one per user. */
    public function findOneByMeal(User|string $user, string $mealId): ?FoodIntake
    {
        return $this->findOneBy(['user' => $user, 'mealId' => $mealId]);
    }
}
