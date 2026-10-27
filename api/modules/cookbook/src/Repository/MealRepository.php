<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Symfony\Component\Uid\Ulid;

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
     * The meals of the range, still to be eaten, that have ingredients to buy
     * and whose owner has not chosen which yet (MAG-300).
     *
     * @return Meal[]
     */
    public function findAwaitingGroceryChoiceForUser(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        $meals = $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->join('m.recipes', 'r')
            ->join('r.ingredients', 'ri')
            ->where('a.user = :user')
            ->andWhere('m.groceryChoiceMadeAt IS NULL')
            ->andWhere('m.date >= :from')
            ->andWhere('m.date >= :today')
            ->andWhere('m.date <= :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->setParameter('to', $to, Types::DATE_IMMUTABLE)
            ->setParameter('today', $this->today(), Types::DATE_IMMUTABLE)
            ->orderBy('m.date', 'ASC')
            ->addOrderBy('m.slot', 'ASC')
            ->getQuery()
            ->getResult();

        // One row per ingredient: a meal with several comes back several times.
        $unique = [];
        foreach ($meals as $meal) {
            $unique[(string) $meal->getId()] = $meal;
        }

        return array_values($unique);
    }

    /**
     * The meals still to be eaten — today included — that name no guests and
     * so are cooked for the user's default (MAG-482).
     *
     * @return Meal[]
     */
    public function findUpcomingOnDefaultGuestsForUser(User $user): array
    {
        return $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('m.guests IS NULL')
            ->andWhere('m.date >= :today')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('today', $this->today(), Types::DATE_IMMUTABLE)
            ->orderBy('m.date', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** The meal, if it is the user's: another user's meal is not found. */
    public function findOneForUser(string $id, User $user): ?Meal
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->where('m.id = :id')
            ->andWhere('a.user = :user')
            ->setParameter('id', Ulid::fromString($id), 'ulid')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The meals of the next `$days` days — today included — whose recipes use
     * the product, soonest first.
     *
     * @return Meal[]
     */
    public function findUpcomingUsingProduct(User $user, Product $product, int $days): array
    {
        $from = $this->today();

        // No DISTINCT: a meal carries JSON columns, which Postgres cannot compare.
        $meals = $this->createQueryBuilder('m')
            ->join('m.agenda', 'a')
            ->join('m.recipes', 'r')
            ->join('r.ingredients', 'ri')
            ->where('a.user = :user')
            ->andWhere('IDENTITY(ri.ingredient) = :product')
            ->andWhere('m.date >= :from')
            ->andWhere('m.date < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('product', $product->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATE_IMMUTABLE)
            ->setParameter('until', $from->modify("+{$days} days"), Types::DATE_IMMUTABLE)
            ->orderBy('m.date', 'ASC')
            ->getQuery()
            ->getResult();

        // Two recipes of one meal may both use the product: the join returns the meal twice.
        $unique = [];
        foreach ($meals as $meal) {
            $unique[(string) $meal->getId()] = $meal;
        }

        return array_values($unique);
    }

    /**
     * Every meal that serves the recipe, past or to come.
     *
     * @return Meal[]
     */
    public function findByRecipe(Recipe $recipe): array
    {
        return $this->byRecipe($recipe)->getQuery()->getResult();
    }

    /**
     * The day of the latest meal already eaten that served each recipe of the user, by recipe id.
     * A recipe never served is not in the answer.
     *
     * @return array<string, \DateTimeImmutable>
     */
    public function findLastEatenDatesByRecipeForUser(User $user): array
    {
        $rows = $this->createQueryBuilder('m')
            ->select('r.id AS recipeId', 'MAX(m.date) AS lastDate')
            ->join('m.agenda', 'a')
            ->join('m.recipes', 'r')
            ->where('a.user = :user')
            ->andWhere('m.date < :today')
            ->groupBy('r.id')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('today', $this->today(), Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getArrayResult();

        $dates = [];
        foreach ($rows as $row) {
            $dates[(string) $row['recipeId']] = new \DateTimeImmutable((string) $row['lastDate']);
        }

        return $dates;
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
        $meals = array_values(array_filter(
            $this->byRecipe($recipe)->getQuery()->getResult(),
            static fn (Meal $meal) => 1 === $meal->getRecipes()->count(),
        ));

        // The slot is stored as text, where « dinner » sorts before « lunch ».
        usort($meals, static fn (Meal $a, Meal $b) => [$a->getDate(), MealSlot::Lunch === $a->getSlot() ? 0 : 1] <=> [$b->getDate(), MealSlot::Lunch === $b->getSlot() ? 0 : 1]);

        return $meals;
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

    public function today(): \DateTimeImmutable
    {
        // Today where the owner lives, not where the server runs: between
        // midnight and 02:00 in Paris, UTC is still the day before, and today's
        // meals would count as past.
        return new \DateTimeImmutable((new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'), new \DateTimeZone('UTC'));
    }
}
