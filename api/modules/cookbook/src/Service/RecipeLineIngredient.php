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
 * from Ciqual when it has a code but no values, and a weight per piece when
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

        $this->ciqualNutrients->fillMissing($ingredient);

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
            return $this->ingredientRepository->find($item['ingredientId'])
                ?? throw new \DomainException("Ingredient not found: {$item['ingredientId']}");
        }

        throw new \DomainException('Each ingredient must have either ingredientId or ciqualAlimCode.');
    }
}
