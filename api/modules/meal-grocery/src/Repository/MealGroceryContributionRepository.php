<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\MealGrocery\Entity\MealGroceryContribution;

/** @extends ServiceEntityRepository<MealGroceryContribution> */
class MealGroceryContributionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, MealGroceryContribution::class);
    }

    /** @return MealGroceryContribution[] */
    public function findByMeal(Meal $meal): array
    {
        return $this->findBy(['meal' => $meal]);
    }

    /**
     * Every contribution held by a grocery line, so a revoke can tell whether
     * anything else still needs it.
     *
     * @return MealGroceryContribution[]
     */
    public function findByGroceryItem(GroceryItem $item): array
    {
        return $this->findBy(['groceryItem' => $item]);
    }

    /**
     * The ids of the meals whose day is before `$today` and which still hold a
     * line that is not in the basket: the meals whose shopping has to come off
     * the list.
     *
     * @return list<string>
     */
    public function findPassedMealIdsWithUnboughtLines(\DateTimeImmutable $today): array
    {
        /** @var MealGroceryContribution[] $contributions */
        $contributions = $this->createQueryBuilder('c')
            ->join('c.meal', 'm')
            ->join('c.groceryItem', 'i')
            ->where('m.date < :today')
            ->andWhere('i.checked = false')
            ->setParameter('today', $today, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getResult();

        $ids = [];
        foreach ($contributions as $contribution) {
            $ids[(string) $contribution->getMeal()->getId()] = true;
        }

        return array_keys($ids);
    }
}
