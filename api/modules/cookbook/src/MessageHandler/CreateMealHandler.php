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
use Maggie\Cookbook\UseCase\CreateMeal;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Repository\GroceryListRepository;
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
                if (null !== $shelfLifeDays) {
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
                    if (null !== $existing->getProduct()
                        && (string) $existing->getProduct()->getId() === (string) $ingredient->getId()
                        && $existing->getUnit() === $unit
                    ) {
                        $existing->setQuantity(($existing->getQuantity() ?? 0) + $ri->getQuantity());
                        // Keep the earlier buyAfter
                        if (null !== $buyAfter && (null === $existing->getBuyAfter() || $buyAfter < $existing->getBuyAfter())) {
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
