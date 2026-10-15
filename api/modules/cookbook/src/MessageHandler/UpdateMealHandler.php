<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealTitle;
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
            $meal->setDate(Meal::dayFromString($command->date));
        }

        if ($command->useDefaultGuests) {
            $meal->setGuests(null);
        } elseif (null !== $command->guests) {
            // A new number scales the meal's shopping: the `MealGuestsChanged` event's business.
            $meal->setGuests(Meal::guestsFrom($command->guests));
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

        $meal->setSummary(MealTitle::of($meal));

        // Moving the meal is the `MealRescheduled` event's business, from whichever door.
        // A recipe swap leaves the list alone: re-synchronising would put back what the
        // owner had unticked. The meal screen offers to review the ingredients (MAG-300).
        return $this->updateMeal->execute($meal);
    }
}
