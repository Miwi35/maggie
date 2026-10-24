<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\UseCase;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Exception\ProductInUseException;
use Maggie\Grocery\UseCase\ProductDeletionGuard;

/** A recipe line points at its ingredient for good: the ingredient stays while a recipe lists it. */
class IngredientDeletionGuard implements ProductDeletionGuard
{
    public function __construct(
        private readonly IngredientRepository $ingredientRepository,
    ) {
    }

    public function assertDeletable(Product $product): void
    {
        if (!$product instanceof Ingredient) {
            return;
        }

        $recipeNames = $this->ingredientRepository->findRecipeNamesUsing($product);
        if ([] === $recipeNames) {
            return;
        }

        throw new ProductInUseException(sprintf('Ingredient "%s" cannot be deleted: it is used in %s. Remove it from the recipe first.', $product->getName(), implode(', ', array_map(static fn (string $name) => '"'.$name.'"', $recipeNames))));
    }
}
