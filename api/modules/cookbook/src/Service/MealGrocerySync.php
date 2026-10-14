<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
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
 * Every line a meal touches is recorded as a {@see MealGroceryContribution}.
 * Since MAG-300 the owner's explicit choice is the only way a meal puts
 * anything on the list: `syncChoice()` makes the list hold exactly the lines
 * chosen for the meal — adding, adjusting in place and taking back — in
 * packagings (MAG-295). Nothing is derived from the recipes any more, so
 * planning a meal, swapping its recipes or editing a recipe leaves the list
 * alone: re-synchronising would put back what the owner had unticked.
 *
 * `sync()` is what is left of the old derivation, and it is deliberately
 * small: it moves the `buyAfter` of the lines a meal holds when the meal moves
 * (MAG-251), at an unchanged quantity — a perishable planned three days later
 * is bought three days later. The contributions a meal planned before MAG-300
 * inherited from the old path stay as they are, in recipe units (no data
 * migration): `sync()` follows them like any other, and `revoke()` takes them
 * back when the meal goes.
 * `Meal::$groceryChoiceMadeAt` is no longer read here: it only tells the
 * "review the ingredients" screen and `generate_grocery_list` which meals
 * still have nothing chosen.
 *
 * `revoke()` takes back what a removed meal had put on, whoever put it there.
 *
 * Flushing: `sync()` flushes, `revoke()` and `syncChoice()` do not. The
 * asymmetry is deliberate. `sync()` runs alone, once per move, and has nothing
 * else to commit with. `revoke()` runs once the meal is already gone (MAG-368),
 * on the shares its removal event carries: its use case flushes, and a failure
 * there leaves the shopping on the list, logged, rather than taking it off a
 * meal still planned. `syncChoice()` must not either, so that the chosen lines
 * and the meal's choice marker commit together. A single pass needs no
 * intermediate flush — `isHeldByAnotherMeal()` excludes its own contribution,
 * and the unique index allows one contribution per meal and line. The line
 * rows are read through the repository rather than `$list->getItems()` — see
 * `itemsOf()` — and that is the other trap of this class (MAG-116).
 *
 * A meal whose day has gone has nothing left to buy (MAG-400): `sync()` and
 * `syncChoice()` add nothing for it, and `release()` takes back what it still
 * held — its unbought share only, a line already in the basket staying as it is.
 *
 * Nothing is broadcast from here: the list's lines are read from Doctrine's
 * change set, and the projection announces the list once the handler is done.
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
     * Moves the lines the meal holds to follow it, without touching what they
     * hold. Returns the list only when a date moved, so an edit that changes
     * nothing on the list publishes nothing.
     */
    public function sync(Meal $meal): ?GroceryList
    {
        if ($this->isPast($meal)) {
            return $this->releaseAndFlush($meal);
        }

        return $this->followMeal($meal);
    }

    /**
     * Makes the list hold exactly the lines the owner chose for this meal.
     *
     * Each line is a product, a unit — the packaging, or the recipe's when the
     * product has none — and a quantity; two lines of one product and unit add
     * up. Whatever the meal held and was not chosen is taken back, a line
     * already in the basket excepted. Does not flush: the caller stamps the
     * meal's choice and commits both at once. (A user with no list yet gets
     * one created and committed on the way — an empty list, nothing of the
     * meal's.)
     *
     * @param list<array{ingredient: Ingredient, unit: Unit, quantity: float}> $chosenLines
     */
    public function syncChoice(Meal $meal, array $chosenLines): ?GroceryList
    {
        if ($this->isPast($meal)) {
            return $this->release($meal);
        }

        $wanted = [];

        foreach ($chosenLines as $line) {
            $key = $this->key((string) $line['ingredient']->getId(), $line['unit']);

            if (isset($wanted[$key])) {
                $wanted[$key]['quantity'] += $line['quantity'];

                continue;
            }

            $wanted[$key] = $line;
        }

        return $this->reconcile($meal, $wanted);
    }

    /**
     * Take back what the meal no longer wants, adjust what it keeps, add what
     * is missing. Does not flush.
     *
     * @param array<string, array{ingredient: Ingredient, unit: Unit, quantity: float}> $wanted
     */
    private function reconcile(Meal $meal, array $wanted): ?GroceryList
    {
        $held = [];
        foreach ($this->contributionRepository->findByMeal($meal) as $contribution) {
            $held[$this->keyOfContribution($contribution)] = $contribution;
        }

        if ([] === $wanted && [] === $held) {
            return null;
        }

        $list = $this->groceryListRepository->findOrCreateForUser($meal->getAgenda()->getUser());
        // The meal's day, which is all a shelf life is counted back from
        // (MAG-251). A meal always has one — `Meal::$date` is not nullable in
        // the database, only on the way in from a client.
        $mealDate = $meal->getDate() ?? throw new \LogicException('A stored meal always has a day.');

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

        return $list;
    }

    /**
     * A meal keeps the lines it holds, at the quantity they have: only their
     * `buyAfter` follows the meal's day.
     *
     * The shelf life is the line's product's — there are no recipe lines to
     * read it from on this path.
     */
    private function followMeal(Meal $meal): ?GroceryList
    {
        $mealDate = $meal->getDate() ?? throw new \LogicException('A stored meal always has a day.');
        $list = null;

        foreach ($this->contributionRepository->findByMeal($meal) as $contribution) {
            $item = $contribution->getGroceryItem();
            $before = $item->getBuyAfter();

            $this->adjust(
                $contribution,
                $contribution->getQuantity(),
                $this->buyAfter($mealDate, $item->getProduct()?->getShelfLifeDays()),
            );

            if ($before?->format('Y-m-d') !== $item->getBuyAfter()?->format('Y-m-d')) {
                $list = $item->getGroceryList();
            }
        }

        if (null === $list) {
            return null;
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }

    /**
     * Takes back what a removed meal had put on the list.
     *
     * The meal and its contributions are already gone — the database cascade
     * erased them with it — so what it had put on each line is given: the line
     * and the quantity, as {@see MealRemoved} carries them.
     *
     * Returns the list it touched, or null when there was nothing to take back
     * — there is then nothing to broadcast. Does not flush: the caller commits.
     *
     * @param list<array{groceryItemId: string, quantity: float}> $released
     */
    public function revoke(array $released): ?GroceryList
    {
        $list = null;

        foreach ($released as $share) {
            $item = $this->em->getRepository(GroceryItem::class)->find($share['groceryItemId']);

            // Already taken off by hand since: nothing left to subtract from.
            if (null === $item) {
                continue;
            }

            $list ??= $item->getGroceryList();
            $this->takeBackShare($item, $share['quantity'], null);
        }

        $list?->setUpdatedAt(new \DateTimeImmutable());

        return $list;
    }

    /**
     * Takes back what a meal whose day has gone still holds on the list.
     *
     * Only its own share of each line that is not in the basket yet: the other
     * meals' shares stay, a line that falls to zero with nothing else holding it
     * goes, and a checked line keeps its quantity *and* its link to the meal.
     * Idempotent — a second call finds nothing left to take back.
     *
     * Returns the list it touched, null when there was nothing to take back.
     * Does not flush, but the caller must before releasing another meal: the
     * lines two past meals share are told apart by the contributions the
     * database still holds.
     */
    public function release(Meal $meal): ?GroceryList
    {
        $list = null;

        foreach ($this->contributionRepository->findByMeal($meal) as $contribution) {
            $item = $contribution->getGroceryItem();

            if ($item->isChecked()) {
                continue;
            }

            $list ??= $item->getGroceryList();
            $this->takeBack($contribution);
        }

        $list?->setUpdatedAt(new \DateTimeImmutable());

        return $list;
    }

    /** A meal planned or moved onto a day already gone: its shopping is not wanted. */
    private function releaseAndFlush(Meal $meal): ?GroceryList
    {
        $list = $this->release($meal);

        if (null !== $list) {
            $this->em->flush();
        }

        return $list;
    }

    /** The meal's day is before today, where the owner lives. */
    private function isPast(Meal $meal): bool
    {
        $date = $meal->getDate();

        return null !== $date && $date < $this->today();
    }

    private function today(): \DateTimeImmutable
    {
        // Both sides are plain days, so this compares days: today where the
        // owner lives, not where the server runs.
        return new \DateTimeImmutable(
            (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            new \DateTimeZone('UTC'),
        );
    }

    /** Drops one contribution and subtracts its share from the line it held. */
    private function takeBack(MealGroceryContribution $contribution): void
    {
        $this->em->remove($contribution);
        $this->takeBackShare($contribution->getGroceryItem(), $contribution->getQuantity(), $contribution);
    }

    /**
     * Subtracts a meal's share from the line. `$own` is the meal's contribution
     * when it still exists, null when it is already gone: every contribution
     * left on the line is then another meal's.
     */
    private function takeBackShare(GroceryItem $item, float $quantity, ?MealGroceryContribution $own): void
    {
        // Already bought: the shopper carried it home, so the line stays as it
        // is — only the link to the meal goes.
        if ($item->isChecked()) {
            return;
        }

        $remaining = ($item->getQuantity() ?? 0.0) - $quantity;
        $heldByAnotherMeal = $this->isHeldByAnotherMeal($item, $own);

        if ($remaining > self::EPSILON) {
            $item->setQuantity($remaining);

            return;
        }

        if (!$heldByAnotherMeal && GroceryItemSource::Recipe === $item->getSource()) {
            $item->getGroceryList()->removeItem($item);
            $this->em->remove($item);

            return;
        }

        // A line the user added by hand, or one another meal still needs: it
        // survives, emptied of this meal's share.
        $item->setQuantity($heldByAnotherMeal ? 0.0 : null);
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

    private function key(string $productId, ?Unit $unit): string
    {
        return $productId.':'.($unit->value ?? '');
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

    private function isHeldByAnotherMeal(GroceryItem $item, ?MealGroceryContribution $own): bool
    {
        foreach ($this->contributionRepository->findByGroceryItem($item) as $other) {
            if (null === $own || (string) $other->getId() !== (string) $own->getId()) {
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

        return $buyAfter > $this->today() ? $buyAfter : null;
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
