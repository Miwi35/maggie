<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\CreateMeal;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateMealHandler
{
    public function __construct(
        private readonly CreateMeal $createMeal,
        private readonly AgendaRepository $agendaRepository,
        private readonly RecipeRepository $recipeRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateMealCommand $command): Meal
    {
        $slot = MealSlot::from($command->slot);

        // Find or create "Repas" agenda
        $agenda = null;
        if ($command->agendaId !== null) {
            $agenda = $this->agendaRepository->find($command->agendaId);
        }
        if ($agenda === null) {
            $agenda = $this->agendaRepository->findOneBy(['name' => 'Repas']);
        }
        if ($agenda === null) {
            // Auto-create the Repas agenda
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? throw new \DomainException('No user found.');

            $agenda = new Agenda();
            $agenda->setUser($user);
            $agenda->setName('Repas');
            $agenda->setColor('#FF6B35');
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
            $recipe = $this->recipeRepository->find($recipeId)
                ?? throw new \DomainException("Recipe not found: {$recipeId}");
            $meal->addRecipe($recipe);
            $recipeNames[] = $recipe->getName();
        }

        $slotLabel = $slot === MealSlot::Lunch ? 'Déjeuner' : 'Dîner';
        $summary = $recipeNames !== []
            ? $slotLabel . ' : ' . implode(', ', $recipeNames)
            : $slotLabel;
        $meal->setSummary($summary);

        return $this->createMeal->execute($meal);
    }
}
