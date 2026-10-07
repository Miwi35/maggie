<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
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
     * The range is a range of days, inclusive on both ends: a meal is a day
     * (MAG-251), so there is no instant to be off by an offset here.
     *
     * @return Meal[]
     */
    public function findByDateRangeForUser(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        return $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('m.date >= :from')
            ->andWhere('m.date <= :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->setParameter('to', $to, Types::DATE_IMMUTABLE)
            ->orderBy('m.date', 'ASC')
            ->addOrderBy('m.slot', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The meals still to be eaten — today included — that serve the recipe.
     *
     * @return Meal[]
     */
    public function findUpcomingByRecipe(Recipe $recipe): array
    {
        return $this->byRecipe($recipe)->andWhere('m.date >= :today')->setParameter('today', $this->today(), Types::DATE_IMMUTABLE)->getQuery()->getResult();
    }

    /**
     * The meals already eaten that served the recipe: shopping done, kept in
     * step by nothing.
     *
     * @return Meal[]
     */
    public function findPastByRecipe(Recipe $recipe): array
    {
        return $this->byRecipe($recipe)->andWhere('m.date < :today')->setParameter('today', $this->today(), Types::DATE_IMMUTABLE)->getQuery()->getResult();
    }

    /**
     * The meals the recipe is the only one of — the meals deleting it takes
     * with it — oldest first.
     *
     * @return Meal[]
     */
    public function findServedOnlyBy(Recipe $recipe): array
    {
        return array_values(array_filter(
            $this->byRecipe($recipe)->addOrderBy('m.slot', 'ASC')->getQuery()->getResult(),
            static fn (Meal $meal) => 1 === $meal->getRecipes()->count(),
        ));
    }

    public function countServedOnlyBy(Recipe $recipe): int
    {
        return \count($this->findServedOnlyBy($recipe));
    }

    private function byRecipe(Recipe $recipe): QueryBuilder
    {
        return $this->createQueryBuilder('m')
            ->join('m.recipes', 'r')
            ->where('r = :recipe')
            ->setParameter('recipe', $recipe->getId(), 'ulid')
            ->orderBy('m.date', 'ASC');
    }

    private function today(): \DateTimeImmutable
    {
        // Today where the owner lives, not where the server runs: between
        // midnight and 02:00 in Paris, UTC is still the day before, and today's
        // meals would count as past.
        return new \DateTimeImmutable((new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'), new \DateTimeZone('UTC'));
    }
}
