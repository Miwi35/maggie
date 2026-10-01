<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Meal> */
class MealRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Meal::class);
    }

    /** Meals belong to an agenda, which belongs to a user.
     *
     * @return Meal[]
     */
    public function findByDateRangeForUser(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        return $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('m.startAt >= :from')
            ->andWhere('m.startAt <= :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from)
            ->setParameter('to', $to)
            ->orderBy('m.startAt', 'ASC')
            ->addOrderBy('m.slot', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The meals still to be eaten — today included — that serve the recipe.
     * Past meals are shopping already done: nothing keeps them in step.
     *
     * @return Meal[]
     */
    public function findUpcomingByRecipe(Recipe $recipe): array
    {
        return $this->createQueryBuilder('m')
            ->join('m.recipes', 'r')
            ->where('r = :recipe')
            ->andWhere('m.startAt >= :today')
            ->setParameter('recipe', $recipe->getId(), 'ulid')
            ->setParameter('today', new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')))
            ->orderBy('m.startAt', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
