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
 * Every line a meal touches is recorded as a {@see MealGroceryContribution}:
 * `apply()` adds and records, `revoke()` subtracts what was recorded, `sync()`
 * does both and is therefore idempotent — which is what makes re-generating a
 * list safe.
 *
 * Callers flush. The grocery list must also be broadcast afterwards
 * (`GroceryListBroadcaster`): a meal handler returns the meal, so the
 * Messenger middleware never sees the list it changed.
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

    /** Re-applies a meal from scratch: safe to call any number of times. */
    public function sync(Meal $meal): ?GroceryList
    {
        $revoked = $this->revoke($meal);

        // The one flush this class does. A meal that keeps an ingredient gets
        // a fresh contribution for a line it already had one for, and Doctrine
        // runs every insert before any delete — the unique (meal, item) index
        // would fire on a change that is really a replacement.
        $this->em->flush();

        return $this->apply($meal) ?? $revoked;
    }

    /**
     * Adds the meal's ingredients to its owner's list, merging and recording.
     *
     * Returns null when the meal brings no ingredient — a slot planned with no
     * recipe changes no shopping, and must not conjure an empty list.
     */
    public function apply(Meal $meal): ?GroceryList
    {
        if (!$this->hasIngredients($meal)) {
            return null;
        }

        $list = $this->groceryListRepository->findOrCreateForUser($meal->getAgenda()->getUser());
        $date = $meal->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'));

        /** @var array<string, MealGroceryContribution> $contributions keyed by product id and unit */
        $contributions = [];

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $ri) {
                $ingredient = $ri->getIngredient();
                $unit = $ri->getUnit();
                $quantity = $ri->getQuantity();
                $buyAfter = $this->buyAfter($date, $ingredient->getShelfLifeDays());

                $key = (string) $ingredient->getId().':'.$unit->value;

                if (isset($contributions[$key])) {
                    // A second recipe in the same meal needing the same thing:
                    // the line already exists, only the amount grows.
                    $contribution = $contributions[$key];
                    $item = $contribution->getGroceryItem();
                    $item->setQuantity(($item->getQuantity() ?? 0.0) + $quantity);
                    $contribution->addQuantity($quantity);
                    $this->keepEarliestBuyAfter($item, $buyAfter);

                    continue;
                }

                $item = $this->findMergeable($list, (string) $ingredient->getId(), $unit);

                if (null !== $item) {
                    $item->setQuantity(($item->getQuantity() ?? 0.0) + $quantity);
                    $this->keepEarliestBuyAfter($item, $buyAfter);
                } else {
                    $item = new GroceryItem();
                    $item->setProduct($ingredient);
                    $item->setQuantity($quantity);
                    $item->setUnit($unit);
                    $item->setSource(GroceryItemSource::Recipe);
                    $item->setStore($ingredient->getPreferredStore());
                    $item->setBuyAfter($buyAfter);
                    $item->setPosition($this->nextPosition($list));
                    $list->addItem($item);
                    $this->em->persist($item);
                }

                $contribution = new MealGroceryContribution();
                $contribution->setMeal($meal);
                $contribution->setGroceryItem($item);
                $contribution->setQuantity($quantity);
                $contribution->setUnit($unit);
                $this->em->persist($contribution);

                $contributions[$key] = $contribution;
            }
        }

        $list->setUpdatedAt(new \DateTimeImmutable());

        return $list;
    }

    /**
     * Takes back what the meal put on the list.
     *
     * Returns the list it touched, or null when the meal never contributed —
     * there is then nothing to broadcast.
     */
    public function revoke(Meal $meal): ?GroceryList
    {
        $contributions = $this->contributionRepository->findByMeal($meal);

        if ([] === $contributions) {
            return null;
        }

        $list = null;

        foreach ($contributions as $contribution) {
            $item = $contribution->getGroceryItem();
            $list ??= $item->getGroceryList();

            $this->em->remove($contribution);

            // Already bought: the shopper carried it home, so the line stays
            // as it is — only the link to the meal goes.
            if ($item->isChecked()) {
                continue;
            }

            $remaining = ($item->getQuantity() ?? 0.0) - $contribution->getQuantity();
            $heldByAnotherMeal = $this->isHeldByAnotherMeal($item, $contribution);

            if ($remaining > self::EPSILON) {
                $item->setQuantity($remaining);

                continue;
            }

            if (!$heldByAnotherMeal && GroceryItemSource::Recipe === $item->getSource()) {
                $item->getGroceryList()->removeItem($item);
                $this->em->remove($item);

                continue;
            }

            // A line the user added by hand, or one another meal still needs:
            // it survives, emptied of this meal's share.
            $item->setQuantity($heldByAnotherMeal ? 0.0 : null);
        }

        $list?->setUpdatedAt(new \DateTimeImmutable());

        return $list;
    }

    private function hasIngredients(Meal $meal): bool
    {
        foreach ($meal->getRecipes() as $recipe) {
            if ($recipe->getIngredients()->count() > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * The unchecked line this product and unit should join, if any.
     *
     * Checked lines are skipped: adding to something already in the basket
     * would hide the new need.
     */
    private function findMergeable(GroceryList $list, string $productId, Unit $unit): ?GroceryItem
    {
        foreach ($list->getItems() as $existing) {
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

    private function isHeldByAnotherMeal(GroceryItem $item, MealGroceryContribution $revoked): bool
    {
        foreach ($this->contributionRepository->findByGroceryItem($item) as $other) {
            if ((string) $other->getId() !== (string) $revoked->getId()) {
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

    private function nextPosition(GroceryList $list): int
    {
        $max = 0;

        foreach ($list->getItems() as $existing) {
            $max = max($max, $existing->getPosition());
        }

        return $max + 1;
    }
}
