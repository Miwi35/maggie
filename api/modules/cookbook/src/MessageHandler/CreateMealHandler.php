<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\UseCase\CreateMeal;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateMealHandler
{
    public function __construct(
        private readonly CreateMeal $createMeal,
        private readonly AgendaRepository $agendaRepository,
        private readonly RecipeRepository $recipeRepository,
        private readonly UserRepository $userRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly GroceryListBroadcaster $groceryListBroadcaster,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateMealCommand $command): Meal
    {
        $slot = MealSlot::from($command->slot);

        // Find or create the current user's "Repas" agenda
        $agenda = null;
        if (null !== $command->agendaId) {
            $agenda = $this->agendaRepository->find($command->agendaId);
        }
        if (null === $agenda) {
            $user = (null !== $command->userId ? $this->userRepository->find($command->userId) : null)
                ?? throw new \DomainException('No user found.');

            $agenda = $this->agendaRepository->findOneBy(['name' => 'Repas', 'user' => $user]);
        }
        if (null === $agenda) {
            // Auto-create the Repas agenda
            $agenda = new Agenda();
            $agenda->setUser($user);
            $agenda->setName('Repas');
            $agenda->setColor('#FF6B35');
            $this->em->persist($agenda);
        }

        $date = new \DateTimeImmutable($command->date, new \DateTimeZone('Europe/Paris'));
        $startAt = $date->setTime(0, 0);
        $endAt = $date->setTime(23, 59, 59);

        $meal = new Meal();
        $meal->setSlot($slot);
        $meal->setAgenda($agenda);
        $meal->setAllDay(true);
        $meal->setStartAt($startAt);
        $meal->setEndAt($endAt);

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

        $list = $this->mealGrocerySync->apply($meal);
        $this->em->flush();

        // The handler returns the meal, so nothing else pushes the list.
        $this->groceryListBroadcaster->broadcast($list);

        return $meal;
    }
}
