<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\UseCase\CreateMeal;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateMealHandler
{
    public function __construct(
        private readonly CreateMeal $createMeal,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly RecipeRepository $recipeRepository,
        private readonly UserRepository $userRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly GroceryListBroadcaster $groceryListBroadcaster,
        private readonly EntityBroadcaster $entityBroadcaster,
    ) {
    }

    public function __invoke(CreateMealCommand $command): Meal
    {
        $slot = MealSlot::from($command->slot);

        $user = (null !== $command->userId ? $this->userRepository->find($command->userId) : null)
            ?? throw new \DomainException('No user found.');

        // Whatever agenda a client had in mind, a meal is filed in the meals' module
        // agenda: it is internal, and Google never sees it (MAG-324).
        [$agenda, $agendaCreated] = $this->moduleAgendas->forUser($user, Agenda::MODULE_COOKBOOK, 'Repas', '#FF6B35');

        $meal = new Meal();
        $meal->setSlot($slot);
        $meal->setAgenda($agenda);
        // The day is the meal's reference; setDate derives the instants the
        // agenda shows it on (MAG-251).
        $meal->setDate(Meal::dayFromString($command->date));

        // Add recipes and build summary
        $recipeNames = [];
        foreach ($command->recipeIds as $recipeId) {
            $recipe = $this->recipeRepository->findOneForUser($recipeId, $agenda->getUser())
                ?? throw new \DomainException("Recipe not found: {$recipeId}");
            $meal->addRecipe($recipe);
            $recipeNames[] = $recipe->getName();
        }

        $slotLabel = MealSlot::Lunch === $slot ? 'Déjeuner' : 'Dîner';
        $summary = [] !== $recipeNames
            ? $slotLabel.' : '.implode(', ', $recipeNames)
            : $slotLabel;
        $meal->setSummary($summary);

        $meal = $this->createMeal->execute($meal);

        // The handler returns the meal, so nothing else pushes the agenda
        // created on the way or the list.
        if ($agendaCreated) {
            $this->entityBroadcaster->broadcast($agenda);
        }
        $this->groceryListBroadcaster->broadcast($this->mealGrocerySync->sync($meal));

        return $meal;
    }
}
