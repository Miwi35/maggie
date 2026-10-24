<?php

declare(strict_types=1);

namespace Maggie\Grocery\Port;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Service\Needs;

/**
 * What the planned meals ask of the products, as grocery sees it: the add flow,
 * the home card and the stock tool read it, and know nothing of meals. The
 * module that does (meal-grocery) answers.
 *
 * @phpstan-import-type Need from Needs
 */
interface RecipeDemandInterface
{
    /**
     * The product's need over the week to come: the sum of what every planned meal
     * asks (`needs`, one entry per unit that cannot be added) and, per meal, what
     * it asks (`meals`).
     *
     * @return array{
     *     needs: list<Need>,
     *     meals: list<array{mealId: string, name: string, date: string, quantity: float, unit: ?string}>,
     * }
     */
    public function forProduct(User $user, Product $product): array;

    /**
     * A new line holds the meals of {@see forProduct()} as the meal sync would
     * have: the later sync of one of them adjusts its share instead of adding
     * it a second time. Sets the line's need.
     *
     * @param list<array{mealId: string, quantity: float, unit: ?string}> $meals
     */
    public function hold(GroceryItem $item, Product $product, array $meals): void;

    /**
     * The ingredients of the meals of today and tomorrow that the owner is low on.
     *
     * @return list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string}>
     */
    public function runningOut(User $user, \DateTimeImmutable $from): array;

    /**
     * The meals of the next `$days` days whose recipes use the product, soonest first.
     *
     * @return list<array{date: string, slot: string, recipes: list<string>}>
     */
    public function plannedMeals(User $user, Product $product, int $days): array;

    /**
     * The entities the demand is read from: a row changing in one of them moves the home card.
     *
     * @return list<class-string>
     */
    public function watchedEntities(): array;
}
