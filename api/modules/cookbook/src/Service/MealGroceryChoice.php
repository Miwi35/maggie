<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\PackagedQuantity;
use Maggie\Grocery\Service\Packaging;

/**
 * What a meal could put on the grocery list, and what the owner chose of it
 * (MAG-295).
 *
 * The preview reads the meal's recipes, one line per product and recipe unit
 * — the quantities of two recipes added up, as the derived path does — and
 * says how many packagings that is, and whether the product's stock makes it
 * worth buying: `suggested` is what a client ticks by default. The choice
 * turns the picked ingredients into the lines {@see MealGrocerySync::syncChoice()}
 * reconciles the list with.
 */
class MealGroceryChoice
{
    public function __construct(
        private readonly Packaging $packaging,
    ) {
    }

    /**
     * @return array{mealId: string, groceryChoiceMadeAt: ?string, ingredients: list<array<string, mixed>>}
     */
    public function preview(Meal $meal): array
    {
        $ingredients = [];

        foreach ($this->recipeLines($meal) as $line) {
            $ingredient = $line['ingredient'];
            $buy = $this->toBuy($line);

            $ingredients[] = [
                'ingredientId' => (string) $ingredient->getId(),
                'name' => $ingredient->getName(),
                'quantity' => $line['quantity'],
                'unit' => $line['unit']->value,
                'packaging' => null === $ingredient->getPackagingUnit() ? null : [
                    'unit' => $ingredient->getPackagingUnit()->value,
                    'size' => $ingredient->getPackagingSize(),
                    'sizeUnit' => $ingredient->getPackagingSizeUnit()?->value,
                ],
                'packagedQuantity' => null !== $buy['packaged'] ? $buy['quantity'] : null,
                'toBuy' => ['quantity' => $buy['quantity'], 'unit' => $buy['unit']->value],
                // Without a packaging there is nothing to convert: the recipe
                // quantity is exactly what goes on the list.
                'converted' => null === $buy['packaged'] || $buy['packaged']->converted,
                'stockState' => $ingredient->getStockState()->value,
                'suggested' => ProductStockState::InStock !== $ingredient->getStockState(),
            ];
        }

        return [
            'mealId' => (string) $meal->getId(),
            'groceryChoiceMadeAt' => $meal->getGroceryChoiceMadeAt()?->format('c'),
            'ingredients' => $ingredients,
        ];
    }

    /**
     * The lines the chosen ingredients put on the list, in packagings.
     *
     * `$chosen` maps an ingredient id to the number of packagings to force, or
     * null for what the recipes ask, rounded up. A product without packaging
     * goes in its recipe unit, and a forced quantity is then in that unit.
     *
     * @param array<string, float|null> $chosen
     *
     * @return list<array{ingredient: Ingredient, unit: Unit, quantity: float}>
     *
     * @throws \DomainException when an ingredient is in none of the meal's recipes
     */
    public function chosenLines(Meal $meal, array $chosen): array
    {
        $byIngredient = [];
        foreach ($this->recipeLines($meal) as $line) {
            $byIngredient[(string) $line['ingredient']->getId()][] = $line;
        }

        $unknown = array_diff(array_map('strval', array_keys($chosen)), array_keys($byIngredient));
        if ([] !== $unknown) {
            throw new \DomainException('Not an ingredient of this meal\'s recipes: '.implode(', ', $unknown).'.');
        }

        $lines = [];

        foreach ($chosen as $ingredientId => $forced) {
            $ingredientLines = [];

            foreach ($byIngredient[(string) $ingredientId] as $line) {
                $buy = $this->toBuy($line);
                $ingredientLines[] = ['ingredient' => $line['ingredient'], 'unit' => $buy['unit'], 'quantity' => (float) $buy['quantity']];
            }

            if (null !== $forced) {
                // One quantity for the product, so one line: in its packaging,
                // or in the first unit its recipes use when it has none.
                $ingredientLines = [['ingredient' => $ingredientLines[0]['ingredient'], 'unit' => $ingredientLines[0]['unit'], 'quantity' => $forced]];
            }

            array_push($lines, ...$ingredientLines);
        }

        return $lines;
    }

    /**
     * @param array{ingredient: Ingredient, unit: Unit, quantity: float} $line
     *
     * @return array{quantity: float|int, unit: Unit, packaged: ?PackagedQuantity}
     */
    private function toBuy(array $line): array
    {
        $packaged = $this->packaging->packagedQuantity($line['ingredient'], $line['quantity'], $line['unit']);

        if (null === $packaged) {
            return ['quantity' => $line['quantity'], 'unit' => $line['unit'], 'packaged' => null];
        }

        return ['quantity' => $packaged->count, 'unit' => $packaged->unit, 'packaged' => $packaged];
    }

    /**
     * The meal's recipe ingredients, one line per product and unit, in the
     * order the recipes list them.
     *
     * @return list<array{ingredient: Ingredient, unit: Unit, quantity: float}>
     */
    private function recipeLines(Meal $meal): array
    {
        $lines = [];

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $ri) {
                $key = $ri->getIngredient()->getId().':'.$ri->getUnit()->value;

                if (isset($lines[$key])) {
                    $lines[$key]['quantity'] += $ri->getQuantity();

                    continue;
                }

                $lines[$key] = ['ingredient' => $ri->getIngredient(), 'unit' => $ri->getUnit(), 'quantity' => $ri->getQuantity()];
            }
        }

        return array_values($lines);
    }
}
