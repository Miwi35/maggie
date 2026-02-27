<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\CiqualFood;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Enum\ProductCategory;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Core\Entity\User;

class IngredientFromCiqualResolver
{
    private const NUTRIENT_CODES = [
        '328' => 'kcalPer100g',
        '25000' => 'proteinPer100g',
        '31000' => 'carbsPer100g',
        '40000' => 'fatPer100g',
    ];

    private const CATEGORY_MAP = [
        'fruit' => ProductCategory::Produce,
        'légume' => ProductCategory::Produce,
        'lait' => ProductCategory::Dairy,
        'fromage' => ProductCategory::Dairy,
        'viande' => ProductCategory::Meat,
        'volaille' => ProductCategory::Meat,
        'poisson' => ProductCategory::Fish,
        'céréal' => ProductCategory::Grain,
        'boisson' => ProductCategory::Beverage,
    ];

    public function __construct(
        private readonly IngredientRepository $ingredientRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function resolve(CiqualFood $ciqualFood, User $user): Ingredient
    {
        $existing = $this->ingredientRepository->findOneByUserAndCiqualFood($user, $ciqualFood);
        if ($existing !== null) {
            return $existing;
        }

        $ingredient = new Ingredient();
        $ingredient->setName($ciqualFood->getAlimNameFr());
        $ingredient->setUser($user);
        $ingredient->setCiqualFood($ciqualFood);
        $ingredient->setDefaultUnit(Unit::Gram);
        $ingredient->setCategory($this->mapCategory($ciqualFood->getAlimGroupNameFr()));

        foreach ($ciqualFood->getNutrients() as $fn) {
            $code = $fn->getNutrient()->getConstCode();
            if (isset(self::NUTRIENT_CODES[$code]) && $fn->getValue() !== null) {
                $setter = 'set' . ucfirst(self::NUTRIENT_CODES[$code]);
                $ingredient->$setter($fn->getValue());
            }
        }

        $this->em->persist($ingredient);

        return $ingredient;
    }

    private function mapCategory(?string $groupName): ProductCategory
    {
        if ($groupName === null) {
            return ProductCategory::Other;
        }

        $lower = mb_strtolower($groupName);
        foreach (self::CATEGORY_MAP as $keyword => $category) {
            if (str_contains($lower, $keyword)) {
                return $category;
            }
        }

        return ProductCategory::Other;
    }
}
