<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealTitle;
use Maggie\Cookbook\UseCase\CreateMeal;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateMealHandler
{
    public function __construct(
        private readonly CreateMeal $createMeal,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly RecipeRepository $recipeRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateMealCommand $command): Meal
    {
        $slot = MealSlot::from($command->slot);

        $user = (null !== $command->userId ? $this->userRepository->find($command->userId) : null)
            ?? throw new \DomainException('No user found.');

        // Whatever agenda a client had in mind, a meal is filed in the meals' module
        // agenda: it is internal, and Google never sees it (MAG-324).
        [$agenda] = $this->moduleAgendas->forUser($user, Agenda::MODULE_COOKBOOK, 'Repas', '#FF6B35');

        $meal = new Meal();
        $meal->setSlot($slot);
        $meal->setAgenda($agenda);
        // The day is the meal's reference; setDate derives the instants the
        // agenda shows it on (MAG-251).
        $meal->setDate(Meal::dayFromString($command->date));
        $meal->setGuests(Meal::guestsFrom($command->guests));

        foreach ($command->recipeIds as $recipeId) {
            $recipe = $this->recipeRepository->findOneForUser($recipeId, $agenda->getUser())
                ?? throw new \DomainException("Recipe not found: {$recipeId}");
            $meal->addRecipe($recipe);
        }

        $meal->setCustomTitle($command->customTitle);
        $meal->setSummary(MealTitle::of($meal));

        // Nothing goes on the grocery list here: the owner chooses which
        // ingredients to buy afterwards (MAG-300).
        return $this->createMeal->execute($meal);
    }
}
