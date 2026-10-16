<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeStep;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Exception\InvalidRecipeStepsException;
use Maggie\Cookbook\State\RecipeStepsPayload;

/**
 * Writes the steps of a recipe and keeps them in line with the rest of it
 * (MAG-490). Nothing is flushed: the recipe's flush does it, and a refusal
 * comes before it, so nothing is written.
 *
 * @phpstan-import-type StepInput from RecipeStepsPayload
 */
class RecipeSteps
{
    public function __construct(
        private readonly RecipeUtensils $recipeUtensils,
    ) {
    }

    /**
     * Replaces the whole list. A step keeps its id while its position stays;
     * a utensil a step uses is added to what the recipe is made with.
     *
     * @param list<StepInput> $inputs
     *
     * @throws InvalidRecipeStepsException
     */
    public function replace(Recipe $recipe, array $inputs): void
    {
        $current = [];
        foreach ($recipe->getSteps() as $step) {
            $current[$step->getPosition()] = $step;
        }

        $ingredients = $this->ingredientsOf($recipe);
        $utensils = [];
        foreach ($recipe->getUtensils() as $utensil) {
            $utensils[self::key($utensil->getName())] = $utensil;
        }

        /** @var array<int, RecipeStep> $steps */
        $steps = [];
        foreach ($inputs as $index => $input) {
            $position = $index + 1;
            $step = $current[$position] ?? (new RecipeStep())->setPosition($position);
            unset($current[$position]);

            $stepUtensils = [];
            foreach ($input['utensils'] as $ref) {
                $stepUtensils[] = $this->utensil($ref, $utensils, $recipe);
            }

            $step->setText($input['text'])
                ->setDurationMinutes($input['durationMinutes'])
                ->setBackground($input['background'])
                ->setIngredients(array_map(fn (array $ref) => $this->ingredient($ref, $ingredients, $position, $input['text']), $input['ingredients']))
                ->setUtensils($stepUtensils)
                ->setWaitsFor(array_map(static fn (int $earlier) => $steps[$earlier], $input['waitsFor']));
            $step->setRecipe($recipe);
            $steps[$position] = $step;

            foreach ($step->linkedUtensils() as $utensil) {
                $recipe->addUtensil($utensil);
            }
        }

        foreach ($current as $gone) {
            $recipe->removeStep($gone);
        }
    }

    /**
     * Refuses a recipe whose steps use an ingredient or a utensil it no longer
     * has — taken out of the recipe while a step still needs it.
     *
     * @throws InvalidRecipeStepsException
     */
    public function assertConsistent(Recipe $recipe): void
    {
        $ingredients = $this->ingredientsOf($recipe);
        $utensils = [];
        foreach ($recipe->getUtensils() as $utensil) {
            $utensils[(string) $utensil->getId()] = true;
        }

        foreach ($recipe->getSteps() as $step) {
            foreach ($step->linkedIngredients() as $ingredient) {
                if (!isset($ingredients['id'][(string) $ingredient->getId()])) {
                    throw new InvalidRecipeStepsException(\sprintf('"%s" cannot leave the recipe: %s uses it. Change the step first.', $ingredient->getName(), self::describe($step->getPosition(), $step->getText())));
                }
            }
            foreach ($step->linkedUtensils() as $utensil) {
                if (!isset($utensils[(string) $utensil->getId()])) {
                    throw new InvalidRecipeStepsException(\sprintf('"%s" cannot leave the recipe: %s uses it. Change the step first.', $utensil->getName(), self::describe($step->getPosition(), $step->getText())));
                }
            }
        }
    }

    /** @return array{id: array<string, Ingredient>, name: array<string, Ingredient>} the recipe's ingredients, by id and by name */
    private function ingredientsOf(Recipe $recipe): array
    {
        $byId = [];
        $byName = [];
        foreach ($recipe->getIngredients() as $line) {
            $ingredient = $line->getIngredient();
            $byId[(string) $ingredient->getId()] = $ingredient;
            $byName[self::key($ingredient->getName())] ??= $ingredient;
        }

        return ['id' => $byId, 'name' => $byName];
    }

    /**
     * @param array{id: string}|array{name: string}                                 $ref
     * @param array{id: array<string, Ingredient>, name: array<string, Ingredient>} $ingredients
     */
    private function ingredient(array $ref, array $ingredients, int $position, string $text): Ingredient
    {
        $ingredient = isset($ref['id']) ? ($ingredients['id'][$ref['id']] ?? null) : ($ingredients['name'][self::key($ref['name'])] ?? null);

        return $ingredient ?? throw new InvalidRecipeStepsException(\sprintf('%s uses "%s", which is not one of the recipe\'s ingredients.', ucfirst(self::describe($position, $text)), $ref['id'] ?? $ref['name']));
    }

    /**
     * A name already among the recipe's utensils — or already added by an
     * earlier step — is that one: a utensil created but not flushed yet is not
     * found again in the catalogue.
     *
     * @param array{id: string}|array{name: string} $ref
     * @param array<string, Utensil>                $utensils by name, filled as the steps are read
     */
    private function utensil(array $ref, array &$utensils, Recipe $recipe): Utensil
    {
        if (isset($ref['name']) && isset($utensils[self::key($ref['name'])])) {
            return $utensils[self::key($ref['name'])];
        }

        $utensil = $this->recipeUtensils->resolve([$ref], $recipe->getUser())[0];
        $utensils[self::key($utensil->getName())] = $utensil;

        return $utensil;
    }

    private static function describe(int $position, string $text): string
    {
        return \sprintf('step %d (« %s »)', $position, mb_strimwidth($text, 0, 40, '…'));
    }

    private static function key(string $name): string
    {
        return mb_strtolower(trim($name));
    }
}
