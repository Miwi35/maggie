<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Repository\MealGroceryContributionRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Repository\GroceryListRepository;

/**
 * Keeps the grocery list in step with the meals that feed it.
 *
 * Planning a meal puts its ingredients on the list; until MAG-116 nothing took
 * them off again, so moving a meal or cancelling it left the shopping to be
 * done anyway, and generating the list a second time added everything twice.
 *
 * Every line a meal touches is recorded as a {@see MealGroceryContribution}.
 * `sync()` makes the list say what the meal needs *now* — adding, adjusting in
 * place and taking back — and `revoke()` takes back everything. Both are
 * idempotent, which is what makes re-generating a list safe.
 *
 * Flushing: `sync()` flushes, `revoke()` does not. The asymmetry is deliberate.
 * `sync()` must, because it asks the database which other meals hold a line and
 * the generation loop syncs one meal after another — an unflushed contribution
 * would make the next meal believe a line is free to delete. `revoke()` must
 * not, so that deleting a meal takes its ingredients off the list and removes
 * the meal in a single transaction: a delete that fails must not leave the
 * shopping already gone.
 *
 * The grocery list must also be broadcast afterwards
 * ({@see \Maggie\Grocery\Service\GroceryListBroadcaster}): a meal handler
 * returns the meal, so the Messenger middleware never sees the list it changed.
 */
class MealGrocerySync
{
    /** Below this, a float quantity is nothing left to buy. */
    private const EPSILON = 0.0001;

    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly MealGroceryContributionRepository $contributionRepository,
    ) {
    }

    /**
     * Makes the list hold exactly what this meal needs, no more and no less.
     *
     * Returns the list it touched, or null when the meal neither needs nor
     * ever needed anything — a slot planned with no recipe changes no
     * shopping, and must not conjure an empty list.
     */
    public function sync(Meal $meal): ?GroceryList
    {
        $wanted = $this->wantedLines($meal);
        $held = [];
        foreach ($this->contributionRepository->findByMeal($meal) as $contribution) {
            $held[$this->keyOfContribution($contribution)] = $contribution;
        }

        if ([] === $wanted && [] === $held) {
            return null;
        }

        $list = $this->groceryListRepository->findOrCreateForUser($meal->getAgenda()->getUser());
        $mealDate = $meal->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'));

        // Read once: nothing below is flushed, so a second read would return
        // the same rows at the cost of another query.
        $items = $this->itemsOf($list);
        $position = $this->highestPosition($items);

        // What the meal no longer needs — a recipe swapped out, an ingredient
        // dropped. Done first, so the lines it still needs are never caught by
        // a contribution this same pass is about to re-create.
        foreach ($held as $key => $contribution) {
            if (isset($wanted[$key])) {
                continue;
            }

            // Out of the running for the rest of this pass, whether the line
            // survived or not. A line this meal has just let go must not be
            // merged back into below: the contribution row is only scheduled
            // for deletion, and Doctrine runs every insert before any delete,
            // so a second contribution for the same pair would break the
            // unique index. It happens when a line's unit is edited by hand
            // and the recipe then asks for that new unit.
            $released = $contribution->getGroceryItem();
            $this->takeBack($contribution);
            unset($held[$key]);

            $items = array_values(array_filter(
                $items,
                static fn (GroceryItem $i) => (string) $i->getId() !== (string) $released->getId(),
            ));
        }

        foreach ($wanted as $key => $line) {
            $buyAfter = $this->buyAfter($mealDate, $line['ingredient']->getShelfLifeDays());
            $contribution = $held[$key] ?? null;

            if (null !== $contribution) {
                // The meal already holds this line: move it by the difference
                // rather than deleting and re-adding. A line keeps its id and
                // its place in the shopper's order through a meal being moved
                // from lunch to dinner.
                $this->adjust($contribution, $line['quantity'], $buyAfter);

                continue;
            }

            $item = $this->findMergeable($items, (string) $line['ingredient']->getId(), $line['unit']);

            if (null !== $item) {
                $item->setQuantity(($item->getQuantity() ?? 0.0) + $line['quantity']);
                $this->keepEarliestBuyAfter($item, $buyAfter);
            } else {
                $item = new GroceryItem();
                $item->setProduct($line['ingredient']);
                $item->setQuantity($line['quantity']);
                $item->setUnit($line['unit']);
                $item->setSource(GroceryItemSource::Recipe);
                $item->setStore($line['ingredient']->getPreferredStore());
                $item->setBuyAfter($buyAfter);
                $item->setPosition(++$position);
                $list->addItem($item);
                $this->em->persist($item);
                $items[] = $item;
            }

            $contribution = new MealGroceryContribution();
            $contribution->setMeal($meal);
            $contribution->setGroceryItem($item);
            $contribution->setQuantity($line['quantity']);
            $contribution->setUnit($line['unit']);
            $this->em->persist($contribution);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }

    /**
     * Takes back everything the meal put on the list.
     *
     * Returns the list it touched, or null when the meal never contributed —
     * there is then nothing to broadcast. Does not flush: the caller commits,
     * so that removing the meal and its shopping is one transaction.
     */
    public function revoke(Meal $meal): ?GroceryList
    {
        $contributions = $this->contributionRepository->findByMeal($meal);

        if ([] === $contributions) {
            return null;
        }

        $list = null;

        foreach ($contributions as $contribution) {
            $list ??= $contribution->getGroceryItem()->getGroceryList();
            $this->takeBack($contribution);
        }

        $list?->setUpdatedAt(new \DateTimeImmutable());

        return $list;
    }

    /**
     * Drops one contribution and subtracts its share from the line it held.
     *
     * Returns the line if it left the list entirely, null if it survived.
     */
    private function takeBack(MealGroceryContribution $contribution): ?GroceryItem
    {
        $item = $contribution->getGroceryItem();
        $this->em->remove($contribution);

        // Already bought: the shopper carried it home, so the line stays as it
        // is — only the link to the meal goes.
        if ($item->isChecked()) {
            return null;
        }

        $remaining = ($item->getQuantity() ?? 0.0) - $contribution->getQuantity();
        $heldByAnotherMeal = $this->isHeldByAnotherMeal($item, $contribution);

        if ($remaining > self::EPSILON) {
            $item->setQuantity($remaining);

            return null;
        }

        if (!$heldByAnotherMeal && GroceryItemSource::Recipe === $item->getSource()) {
            $item->getGroceryList()->removeItem($item);
            $this->em->remove($item);

            return $item;
        }

        // A line the user added by hand, or one another meal still needs: it
        // survives, emptied of this meal's share.
        $item->setQuantity($heldByAnotherMeal ? 0.0 : null);

        return null;
    }

    /** Moves a line the meal still needs by the difference, in place. */
    private function adjust(MealGroceryContribution $contribution, float $quantity, ?\DateTimeImmutable $buyAfter): void
    {
        $item = $contribution->getGroceryItem();
        $delta = $quantity - $contribution->getQuantity();
        $contribution->setQuantity($quantity);

        // Already in the basket: neither the amount nor the date means
        // anything to the shopper any more.
        if ($item->isChecked()) {
            return;
        }

        if (abs($delta) > self::EPSILON) {
            $item->setQuantity(max(0.0, ($item->getQuantity() ?? 0.0) + $delta));
        }

        // A line this meal owns outright follows it: moved three days later,
        // a perishable is bought three days later. Anywhere else — shared
        // with another meal, or a line the user wrote and perhaps deferred
        // themselves — the date may only come earlier, never be overwritten.
        if (GroceryItemSource::Recipe === $item->getSource() && !$this->isHeldByAnotherMeal($item, $contribution)) {
            $item->setBuyAfter($buyAfter);

            return;
        }

        $this->keepEarliestBuyAfter($item, $buyAfter);
    }

    /**
     * What the meal needs, keyed by product and unit, the quantities of its
     * recipes already added up.
     *
     * @return array<string, array{ingredient: \Maggie\Cookbook\Entity\Ingredient, unit: Unit, quantity: float}>
     */
    private function wantedLines(Meal $meal): array
    {
        $lines = [];

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $ri) {
                $key = $this->key((string) $ri->getIngredient()->getId(), $ri->getUnit());

                if (isset($lines[$key])) {
                    $lines[$key]['quantity'] += $ri->getQuantity();

                    continue;
                }

                $lines[$key] = [
                    'ingredient' => $ri->getIngredient(),
                    'unit' => $ri->getUnit(),
                    'quantity' => $ri->getQuantity(),
                ];
            }
        }

        return $lines;
    }

    private function key(string $productId, ?Unit $unit): string
    {
        return $productId.':'.($unit?->value ?? '');
    }

    private function keyOfContribution(MealGroceryContribution $contribution): string
    {
        $product = $contribution->getGroceryItem()->getProduct();

        return $this->key(null !== $product ? (string) $product->getId() : '', $contribution->getUnit());
    }

    /**
     * The unchecked line this product and unit should join, if any.
     *
     * Checked lines are skipped: adding to something already in the basket
     * would hide the new need.
     *
     * @param GroceryItem[] $items
     */
    private function findMergeable(array $items, string $productId, Unit $unit): ?GroceryItem
    {
        foreach ($items as $existing) {
            if ($existing->isChecked()) {
                continue;
            }

            $product = $existing->getProduct();

            if (null !== $product && (string) $product->getId() === $productId && $existing->getUnit() === $unit) {
                return $existing;
            }
        }

        return null;
    }

    private function isHeldByAnotherMeal(GroceryItem $item, MealGroceryContribution $own): bool
    {
        foreach ($this->contributionRepository->findByGroceryItem($item) as $other) {
            if ((string) $other->getId() !== (string) $own->getId()) {
                return true;
            }
        }

        return false;
    }

    /**
     * When the ingredient keeps, buying it earlier than needed is waste; the
     * line stays hidden until its shelf life makes it worth carrying home.
     */
    private function buyAfter(\DateTimeImmutable $mealDate, ?int $shelfLifeDays): ?\DateTimeImmutable
    {
        if (null === $shelfLifeDays) {
            return null;
        }

        $buyAfter = $mealDate->modify("-{$shelfLifeDays} days");

        return $buyAfter > new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')) ? $buyAfter : null;
    }

    private function keepEarliestBuyAfter(GroceryItem $item, ?\DateTimeImmutable $buyAfter): void
    {
        if (null === $buyAfter) {
            return;
        }

        $current = $item->getBuyAfter();

        if (null === $current || $buyAfter < $current) {
            $item->setBuyAfter($buyAfter);
        }
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
     * (EndErrandHandler documents the same trap). Here that would turn every
     * merge into an append — the bug this class exists to fix.
     *
     * @return GroceryItem[]
     */
    private function itemsOf(GroceryList $list): array
    {
        return $this->em->getRepository(GroceryItem::class)->findBy(['groceryList' => $list]);
    }
}
