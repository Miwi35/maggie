<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\CreateMeal;
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
        private readonly GroceryListRepository $groceryListRepository,
        private readonly EntityManagerInterface $em,
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

        $meal = $this->createMeal->execute($meal);

        // Auto-add recipe ingredients to the grocery list
        $user = $agenda->getUser();
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $ri) {
                $ingredient = $ri->getIngredient();
                $unit = $ri->getUnit();

                // Compute buyAfter from shelf life
                $buyAfter = null;
                $shelfLifeDays = $ingredient->getShelfLifeDays();
                if ($shelfLifeDays !== null) {
                    $buyAfter = $date->modify("-{$shelfLifeDays} days");
                    if ($buyAfter <= new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris'))) {
                        $buyAfter = null; // Already past or today → immediately visible
                    }
                }

                // Check for existing unchecked item with same product+unit to merge
                $merged = false;
                foreach ($list->getItems() as $existing) {
                    if ($existing->isChecked()) {
                        continue;
                    }
                    if ($existing->getProduct() !== null
                        && (string) $existing->getProduct()->getId() === (string) $ingredient->getId()
                        && $existing->getUnit() === $unit
                    ) {
                        $existing->setQuantity(($existing->getQuantity() ?? 0) + $ri->getQuantity());
                        // Keep the earlier buyAfter
                        if ($buyAfter !== null && ($existing->getBuyAfter() === null || $buyAfter < $existing->getBuyAfter())) {
                            $existing->setBuyAfter($buyAfter);
                        }
                        $merged = true;
                        break;
                    }
                }

                if (!$merged) {
                    $item = new GroceryItem();
                    $item->setProduct($ingredient);
                    $item->setQuantity($ri->getQuantity());
                    $item->setUnit($unit);
                    $item->setSource(GroceryItemSource::Recipe);
                    $item->setStore($ingredient->getPreferredStore());
                    $item->setBuyAfter($buyAfter);
                    $list->addItem($item);
                }
            }
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $meal;
    }
}
