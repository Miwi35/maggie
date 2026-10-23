<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\Needs;

/**
 * What the meals planned over the next days need of a product (MAG-573), in
 * base units and scaled to each meal's guests. One service, one window: the
 * grocery add flow reads a week, the « missing for today and tomorrow »
 * interruption (MAG-570) reads two days.
 *
 * @phpstan-import-type Need from Needs
 */
class RecipeNeeds
{
    /** Today and the six days after it. */
    public const WEEK_DAYS = 7;

    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly MealGroceryChoice $choice,
        private readonly MealGuests $guests,
    ) {
    }

    /**
     * The product's need over `$days` days from today, today included: the sum
     * of what every planned meal asks (`needs`, one entry per unit that cannot
     * be added) and, per meal, what it asks (`meals`, by date).
     *
     * @return array{
     *     needs: list<Need>,
     *     meals: list<array{mealId: string, name: string, date: string, quantity: float, unit: ?string}>,
     * }
     */
    public function forProduct(User $user, Product $product, int $days = self::WEEK_DAYS): array
    {
        $from = $this->mealRepository->today();
        $to = $from->modify('+'.(max(1, $days) - 1).' days');
        $prefix = $product->getId().':';

        $needs = [];
        $meals = [];

        foreach ($this->mealRepository->findByDateRangeForUser($user, $from, $to) as $meal) {
            foreach ($this->choice->needsByProductAndUnit($meal, $this->guests->of($meal)) as $key => $quantity) {
                if (!str_starts_with($key, $prefix)) {
                    continue;
                }

                $unit = '' === ($value = substr($key, \strlen($prefix))) ? null : Unit::from($value);
                $needs = Needs::add($needs, $quantity, $unit);
                $meals[] = [
                    'mealId' => (string) $meal->getId(),
                    'name' => MealTitle::of($meal),
                    'date' => $meal->getDate()?->format('Y-m-d') ?? '',
                    'quantity' => $quantity,
                    'unit' => $unit?->value,
                ];
            }
        }

        return ['needs' => $needs, 'meals' => $meals];
    }

    /**
     * The ingredients the meals of `$days` days from `$from` need and the
     * owner is low on or out of (MAG-563): one entry per product, at the
     * first meal that asks for it, in the order the meals come.
     *
     * @return list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string}>
     */
    public function runningOut(User $user, \DateTimeImmutable $from, int $days = 2): array
    {
        $out = [];
        foreach ($this->runningOutPerMeal($user, $from, $days) as $entry) {
            $out[$entry['productId']] ??= $entry;
        }

        return array_values($out);
    }

    /**
     * Same ingredients, one entry per meal that asks for one (MAG-570): the
     * interruption speaks of a product for each meal, not only the first.
     *
     * @return list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string}>
     */
    public function runningOutPerMeal(User $user, \DateTimeImmutable $from, int $days = 2): array
    {
        $to = $from->modify('+'.(max(1, $days) - 1).' days');
        $meals = $this->mealRepository->findByDateRangeForUser($user, $from, $to);
        usort($meals, static fn ($a, $b) => [$a->getDate(), MealSlot::Lunch === $a->getSlot() ? 0 : 1] <=> [$b->getDate(), MealSlot::Lunch === $b->getSlot() ? 0 : 1]);

        $out = [];
        foreach ($meals as $meal) {
            foreach ($meal->getRecipes() as $recipe) {
                foreach ($recipe->getIngredients() as $line) {
                    $ingredient = $line->getIngredient();
                    $id = (string) $ingredient->getId();
                    $key = $meal->getId().':'.$id;
                    if (isset($out[$key]) || ProductStockState::InStock === $ingredient->getStockState()) {
                        continue;
                    }

                    $out[$key] = [
                        'productId' => $id,
                        'name' => $ingredient->getName(),
                        'date' => $meal->getDate()?->format('Y-m-d') ?? '',
                        'slot' => $meal->getSlot()->value,
                        'mealId' => (string) $meal->getId(),
                        'mealName' => MealTitle::of($meal),
                    ];
                }
            }
        }

        return array_values($out);
    }
}
