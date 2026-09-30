<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\UpdateMeal;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateMealHandler
{
    public function __construct(
        private readonly UpdateMeal $updateMeal,
        private readonly MealRepository $mealRepository,
        private readonly RecipeRepository $recipeRepository,
    ) {
    }

    public function __invoke(UpdateMealCommand $command): Meal
    {
        $meal = $this->mealRepository->find($command->mealId)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        if (null !== $command->slot) {
            $meal->setSlot(MealSlot::from($command->slot));
        }

        if (null !== $command->date) {
            $date = new \DateTimeImmutable($command->date, new \DateTimeZone('Europe/Paris'));
            $meal->setStartAt($date->setTime(0, 0));
            $meal->setEndAt($date->setTime(23, 59, 59));
        }

        if (null !== $command->recipeIds) {
            // Clear and re-add recipes
            foreach ($meal->getRecipes()->toArray() as $recipe) {
                $meal->removeRecipe($recipe);
            }
            foreach ($command->recipeIds as $recipeId) {
                $recipe = $this->recipeRepository->findOneForUser($recipeId, $meal->getAgenda()->getUser())
                    ?? throw new \DomainException("Recipe not found: {$recipeId}");
                $meal->addRecipe($recipe);
            }
        }

        // Regenerate summary
        $recipeNames = $meal->getRecipes()->map(fn ($r) => $r->getName())->toArray();
        $slotLabel = MealSlot::Lunch === $meal->getSlot() ? 'Déjeuner' : 'Dîner';
        $summary = [] !== $recipeNames
            ? $slotLabel.' : '.implode(', ', $recipeNames)
            : $slotLabel;
        $meal->setSummary($summary);

        return $this->updateMeal->execute($meal);
    }
}
