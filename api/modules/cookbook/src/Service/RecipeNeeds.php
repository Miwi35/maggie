<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
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
}
