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
 * Planning a meal puts its ingredients on the list; until MAG-116 nothing took
 * them off again, so moving a meal or cancelling it left the shopping to be
 * done anyway, and generating the list a second time added everything twice.
 *
 * Every line a meal touches is recorded as a {@see MealGroceryContribution}.
 * `sync()` makes the list say what the meal needs *now* — adding, adjusting in
 * place and taking back — and `revoke()` takes back what a removed meal had
 * put on. `sync()` is idempotent, which is what makes re-generating a list safe.
 *
 * `syncChoice()` is the third way in (MAG-295): the owner chose which
 * ingredients to buy, in packagings. It runs the very same reconciliation as
 * `sync()`, with what the meal wants coming from the choice instead of the
 * recipes — so the lines the recipes had put on are taken back, and the guard
 * against re-joining a line just let go is inherited, not rewritten. Once a
 * meal has chosen (`Meal::$groceryChoiceMadeAt`), `sync()` derives nothing
 * and takes nothing back for it: it only moves the `buyAfter` of the lines the
 * meal holds when the meal moves (MAG-251). Without that, a contribution in
 * `pack` would never be wanted by a `sync()` deriving `g`, and the chosen
 * lines would vanish at the meal's next edit.
 *
 * Flushing: `sync()` flushes, `revoke()` and `syncChoice()` do not. The
 * asymmetry is deliberate. `sync()` must, because it asks the database which
 * other meals hold a line and the generation loop syncs one meal after another
 * — an unflushed contribution would make the next meal believe a line is free
 * to delete. `revoke()` runs once the meal is already gone (MAG-368), on the
 * shares its removal event carries: its use case flushes, and a failure there
 * leaves the shopping on the list, logged, rather than taking it off a meal
 * still planned. `syncChoice()` must not
 * either, so that the chosen lines and the meal's choice marker commit
 * together: lines committed without the marker would be taken back by the next
 * `sync()`, which would still take the derived path. A single pass needs no
 * intermediate flush — `isHeldByAnotherMeal()` excludes its own contribution,
 * and the unique index allows one contribution per meal and line.
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
     * Makes the list hold exactly what this meal needs, no more and no less.
     *
     * Returns the list it touched, or null when the meal neither needs nor
     * ever needed anything — a slot planned with no recipe changes no
     * shopping, and must not conjure an empty list.
     */
    public function sync(Meal $meal): ?GroceryList
    {
        if (null !== $meal->getGroceryChoiceMadeAt()) {
            return $this->followMeal($meal);
        }

        $list = $this->reconcile($meal, $this->wantedLines($meal));

        if (null !== $list) {
            $this->em->flush();
        }

        return $list;
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
     * The reconciliation both `sync()` and `syncChoice()` run: take back what
     * the meal no longer wants, adjust what it keeps, add what is missing.
     * Does not flush.
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
     * A meal that has chosen keeps the lines it chose, at the quantity chosen:
     * only their `buyAfter` follows the meal's day, as a derived line's does.
     *
     * The shelf life is the line's product's — there are no recipe lines to
     * read it from on this path. Returns the list only when a date moved, so
     * an edit that changes nothing on the list publishes nothing.
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

    /**
     * What the meal needs, keyed by product and unit, the quantities of its
     * recipes already added up.
     *
     * @return array<string, array{ingredient: Ingredient, unit: Unit, quantity: float}>
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

        // Both sides are plain days, so this compares days: today where the
        // owner lives, not where the server runs.
        $today = new \DateTimeImmutable(
            (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            new \DateTimeZone('UTC'),
        );

        return $buyAfter > $today ? $buyAfter : null;
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
