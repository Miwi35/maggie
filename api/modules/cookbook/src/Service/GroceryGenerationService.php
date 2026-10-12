<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\UseCase\AddRecurringGroceryItem;

/**
 * Fills the grocery list from the meals of a week plus what comes back on its own.
 *
 * Generation is a top-up, not a fresh start: the list already holds what the
 * meals added when they were planned and whatever the user wrote on it. It
 * used to append regardless, so asking twice doubled the shopping, and it
 * added every recurring item whatever its frequency (MAG-116).
 *
 * Meals go through {@see MealGrocerySync}, which is idempotent; recurring
 * items go through {@see AddRecurringGroceryItem}, which adds them only when
 * they are due — the same rule and the same use case as the daily command (MAG-369).
 */
class GroceryGenerationService
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly AddRecurringGroceryItem $addRecurringGroceryItem,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function generate(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): GroceryList
    {
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        foreach ($this->mealRepository->findByDateRangeForUser($user, $from, $to) as $meal) {
            $this->mealGrocerySync->sync($meal);
        }

        // Each item goes through the use case the daily command reaches by
        // event: due is checked there, so generating right after it adds nothing.
        foreach ($this->recurringGroceryItemRepository->findByUser($user) as $recurring) {
            $this->addRecurringGroceryItem->execute($recurring);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}
