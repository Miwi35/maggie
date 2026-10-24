<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Exception\InvalidIngredientReplacementException;
use Maggie\Cookbook\Message\ReplaceRecipeIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\IngredientFromCiqualResolver;
use Maggie\Cookbook\Service\RecipeIngredientReplacer;
use Maggie\Cookbook\Service\RecipeSteps;
use Maggie\Cookbook\UseCase\UpdateRecipe;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Uid\Ulid;

/**
 * @phpstan-import-type TextSuggestion from RecipeIngredientReplacer
 */
#[AsMessageHandler]
class ReplaceRecipeIngredientHandler
{
    public function __construct(
        private readonly UpdateRecipe $updateRecipe,
        private readonly RecipeRepository $recipeRepository,
        private readonly IngredientRepository $ingredientRepository,
        private readonly IngredientFromCiqualResolver $ciqualResolver,
        private readonly RecipeIngredientReplacer $replacer,
        private readonly RecipeSteps $recipeSteps,
    ) {
    }

    /** @return array{recipe: Recipe, suggestions: list<TextSuggestion>} */
    public function __invoke(ReplaceRecipeIngredientCommand $command): array
    {
        $recipe = $this->recipeRepository->find($command->recipeId)
            ?? throw new \DomainException("Recipe not found: {$command->recipeId}");

        $unit = null === $command->unit ? null : (Unit::tryFrom($command->unit)
            ?? throw new InvalidIngredientReplacementException(\sprintf('Unknown unit "%s".', $command->unit)));

        $suggestions = $this->replacer->replace(
            $recipe,
            $command->ingredientId,
            $this->replacement($command, $recipe),
            $command->quantity,
            $unit,
            $command->rewriteText,
        );
        $this->recipeSteps->assertConsistent($recipe);
        $recipe->setUpdatedAt(new \DateTimeImmutable());

        return ['recipe' => $this->updateRecipe->execute($recipe), 'suggestions' => $suggestions];
    }

    private function replacement(ReplaceRecipeIngredientCommand $command, Recipe $recipe): Ingredient
    {
        if (null !== $command->withCiqualAlimCode) {
            return $this->ciqualResolver->resolve($command->withCiqualAlimCode, $recipe->getUser());
        }
        if (null === $command->withIngredientId) {
            throw new InvalidIngredientReplacementException('Name the new ingredient: with.ingredientId or with.ciqualAlimCode.');
        }

        return (Ulid::isValid($command->withIngredientId) ? $this->ingredientRepository->find($command->withIngredientId) : null)
            ?? throw new InvalidIngredientReplacementException("Ingredient not found: {$command->withIngredientId}");
    }
}
