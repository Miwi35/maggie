<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;

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
        private readonly CiqualClient $ciqualClient,
    ) {
    }

    public function resolve(string $ciqualAlimCode, User $user): Ingredient
    {
        $existing = $this->ingredientRepository->findOneByUserAndCiqualAlimCode($user, $ciqualAlimCode);
        if (null !== $existing) {
            return $existing;
        }

        $foodData = $this->ciqualClient->getFood($ciqualAlimCode);
        if (null === $foodData) {
            throw new \DomainException("Ciqual food not found: {$ciqualAlimCode}");
        }

        $ingredient = new Ingredient();
        $ingredient->setName($foodData['alim_name_fr']);
        $ingredient->setUser($user);
        $ingredient->setCiqualAlimCode($ciqualAlimCode);
        $ingredient->setDefaultUnit(Unit::Gram);
        $ingredient->setCategory($this->mapCategory($foodData['alim_group_name_fr'] ?? null));

        // Extract macros from nutrients
        $nutrientMap = [];
        foreach ($foodData['nutrients'] ?? [] as $n) {
            $nutrientMap[$n['const_code']] = $n['value'];
        }

        foreach (self::NUTRIENT_CODES as $code => $field) {
            if (isset($nutrientMap[$code]) && null !== $nutrientMap[$code]) {
                $setter = 'set'.ucfirst($field);
                $ingredient->$setter((float) $nutrientMap[$code]);
            }
        }

        $this->em->persist($ingredient);

        return $ingredient;
    }

    private function mapCategory(?string $groupName): ProductCategory
    {
        if (null === $groupName) {
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
