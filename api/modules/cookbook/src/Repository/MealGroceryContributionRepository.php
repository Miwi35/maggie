<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Grocery\Entity\GroceryItem;

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
}
