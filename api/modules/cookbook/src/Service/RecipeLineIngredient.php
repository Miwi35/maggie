<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\PieceWeightCatalog;

/**
 * The ingredient behind a line of a recipe being written (MAG-647). Beyond
 * finding it, the line makes sure the recipe's nutrition can count it: macros
 * and micronutrients from Ciqual when it has a code but no values, and a weight per piece when
 * the line is counted in pieces — the one Maggie gave, else the average-weights table.
 */
class RecipeLineIngredient
{
    public function __construct(
        private readonly IngredientRepository $ingredientRepository,
        private readonly IngredientFromCiqualResolver $ciqualResolver,
        private readonly CiqualNutrients $ciqualNutrients,
        private readonly PieceWeightCatalog $pieceWeights,
    ) {
    }

    /** @param array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string, pieceWeightGrams?: float|int|string} $item */
    public function resolve(array $item, User $user): Ingredient
    {
        $ingredient = $this->find($item, $user);

        if (isset($item['pieceWeightGrams'])) {
            if (!is_numeric($item['pieceWeightGrams']) || (float) $item['pieceWeightGrams'] <= 0) {
                throw new \DomainException('pieceWeightGrams must be a number of grams greater than 0.');
            }
            $ingredient->setPieceWeightGrams((float) $item['pieceWeightGrams']);
        }

        // The macros only for an ingredient with no value at all: a macro cleared on purpose beside others
        // stays cleared. The micronutrients whenever it has none yet (MAG-648).
        $noMacro = null === $ingredient->getKcalPer100g() && null === $ingredient->getProteinPer100g() && null === $ingredient->getCarbsPer100g() && null === $ingredient->getFatPer100g();
        $this->ciqualNutrients->fillMissing($ingredient, $noMacro ? [] : CiqualNutrients::MACRO_FIELDS);

        if (Unit::Piece->value === $item['unit'] && null === $ingredient->getPieceWeightGrams()) {
            $ingredient->setPieceWeightGrams($this->pieceWeights->gramsForProduct($ingredient));
        }

        return $ingredient;
    }

    /** @param array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string} $item */
    private function find(array $item, User $user): Ingredient
    {
        if (isset($item['ciqualAlimCode'])) {
            return $this->ciqualResolver->resolve($item['ciqualAlimCode'], $user);
        }

        if (isset($item['ingredientId'])) {
            $ingredient = $this->ingredientRepository->find($item['ingredientId']);
            if (null === $ingredient || (string) $ingredient->getUser()->getId() !== (string) $user->getId()) {
                throw new \DomainException("Ingredient not found: {$item['ingredientId']}");
            }

            return $ingredient;
        }

        throw new \DomainException('Each ingredient must have either ingredientId or ciqualAlimCode.');
    }
}
