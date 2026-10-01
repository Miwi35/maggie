<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Fills the grocery list from the meals of a week plus what comes back on its own.
 *
 * Generation is a top-up, not a fresh start: the list already holds what the
 * meals added when they were planned and whatever the user wrote on it. It
 * used to append regardless, so asking twice doubled the shopping, and it
 * added every recurring item whatever its frequency (MAG-116).
 *
 * Meals go through {@see MealGrocerySync}, which is idempotent; recurring
 * items are added only when their frequency has run out since the last time.
 */
class GroceryGenerationService
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function generate(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): GroceryList
    {
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        foreach ($this->mealRepository->findByDateRangeForUser($user, $from, $to) as $meal) {
            $this->mealGrocerySync->sync($meal);
        }

        $today = new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris'));

        // Read once, after the meals are in: a line a meal just added must
        // count as already waiting.
        $items = $this->itemsOf($list);
        $position = $this->highestPosition($items);
        $added = [];

        foreach ($this->recurringGroceryItemRepository->findByUser($user) as $recurring) {
            if (!$recurring->isDueOn($today)) {
                continue;
            }

            if (!$this->alreadyOnTheList($items, $recurring)) {
                $item = new GroceryItem();
                $item->setProduct($recurring->getProduct());
                $item->setCustomLabel($recurring->getCustomLabel());
                $item->setQuantity($recurring->getQuantity());
                $item->setUnit($recurring->getUnit());
                $item->setSource(GroceryItemSource::Recurring);
                $item->setStore($recurring->getProduct()?->getPreferredStore());
                $item->setPosition(++$position);
                $list->addItem($item);
                $this->em->persist($item);
                $items[] = $item;
            }

            // Set even when the line was already there: the need is covered,
            // and the clock restarts from the day it was.
            $recurring->setLastAddedAt($today);
            $added[] = $recurring;
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        // After the commit, never before: the indexing command is handled
        // asynchronously, and a worker reading the row ahead of the
        // transaction would index the previous date for ever — the collection
        // is served from Elasticsearch, so the drift would be silent.
        foreach ($added as $recurring) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: RecurringGroceryItem::class,
                entityId: (string) $recurring->getId(),
            ));
        }

        return $list;
    }

    /**
     * An unchecked line for the same product — or the same words — already waiting.
     *
     * @param GroceryItem[] $items
     */
    private function alreadyOnTheList(array $items, RecurringGroceryItem $recurring): bool
    {
        $productId = null !== $recurring->getProduct() ? (string) $recurring->getProduct()->getId() : null;

        foreach ($items as $existing) {
            if ($existing->isChecked()) {
                continue;
            }

            if (null !== $productId) {
                $product = $existing->getProduct();

                if (null !== $product && (string) $product->getId() === $productId) {
                    return true;
                }

                continue;
            }

            if (null !== $recurring->getCustomLabel() && $existing->getLabel() === $recurring->getCustomLabel()) {
                return true;
            }
        }

        return false;
    }

    /** @param GroceryItem[] $items */
    private function highestPosition(array $items): int
    {
        $max = 0;

        foreach ($items as $existing) {
            $max = max($max, $existing->getPosition());
        }

        return $max;
    }

    /**
     * The list's lines, read through the repository rather than through
     * `$list->getItems()`: a lazy ghost proxy can leave the PersistentCollection
     * uninitialized and report no elements when the database has rows
     * (EndErrandHandler documents the same trap). Here that would add every
     * recurring item again, list after list.
     *
     * @return GroceryItem[]
     */
    private function itemsOf(GroceryList $list): array
    {
        return $this->em->getRepository(GroceryItem::class)->findBy(['groceryList' => $list]);
    }
}
