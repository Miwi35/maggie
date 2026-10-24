<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\UseCase\AddRecurringGroceryItem;

/**
 * Tops the grocery list up with what comes back on its own, and names the meals
 * whose ingredients are still to be chosen.
 *
 * Meals put nothing on the list by themselves any more (MAG-300): the owner
 * chooses which of their ingredients to buy, in {@see MealGrocerySync::syncChoice()}.
 * Generation is therefore left with the recurring items, which go through
 * {@see AddRecurringGroceryItem} and are added only when due — the same rule
 * and the same use case as the daily command (MAG-369), so asking twice adds
 * nothing the second time (MAG-116).
 */
class GroceryGenerationService
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly AddRecurringGroceryItem $addRecurringGroceryItem,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function generate(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): GroceryGeneration
    {
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        // Each item goes through the use case the daily command reaches by
        // event: due is checked there, so generating right after it adds nothing.
        foreach ($this->recurringGroceryItemRepository->findByUser($user) as $recurring) {
            $this->addRecurringGroceryItem->execute($recurring);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return new GroceryGeneration(
            $list,
            $this->mealRepository->findAwaitingGroceryChoiceForUser($user, $from, $to),
        );
    }
}
