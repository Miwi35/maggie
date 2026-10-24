<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\MealGrocery\Entity\ShortageAlert;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<ShortageAlert> */
class ShortageAlertRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, ShortageAlert::class);
    }

    public function findOwned(User $user, string $id): ?ShortageAlert
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->findOneBy(['id' => Ulid::fromString($id), 'user' => $user]);
    }

    public function findOneByMealAndProduct(Meal $meal, Product $product): ?ShortageAlert
    {
        return $this->findOneBy(['meal' => $meal, 'product' => $product]);
    }

    /** @return list<ShortageAlert> */
    public function findUnresolved(User $user): array
    {
        /** @var list<ShortageAlert> $alerts */
        $alerts = $this->findBy(['user' => $user, 'resolvedAt' => null]);

        return $alerts;
    }

    /** @return list<ShortageAlert> */
    public function findByMeal(Meal $meal): array
    {
        /** @var list<ShortageAlert> $alerts */
        $alerts = $this->findBy(['meal' => $meal]);

        return $alerts;
    }
}
