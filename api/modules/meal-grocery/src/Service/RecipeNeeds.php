<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Cookbook\Service\MealTitle;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Port\RecipeDemandInterface;
use Maggie\Grocery\Service\Needs;
use Maggie\MealGrocery\Entity\MealGroceryContribution;
use Maggie\MealGrocery\Repository\MealGroceryContributionRepository;

/**
 * What the meals planned over the next days need of a product (MAG-573), in
 * base units and scaled to each meal's guests. One service, one window: the
 * grocery add flow reads a week, the « missing for today and tomorrow »
 * interruption (MAG-570) reads two days.
 *
 * @phpstan-import-type Need from Needs
 */
class RecipeNeeds implements RecipeDemandInterface
{
    /** Today and the six days after it. */
    public const WEEK_DAYS = 7;

    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly MealGroceryChoice $choice,
        private readonly MealGuests $guests,
        private readonly EntityManagerInterface $em,
        private readonly MealGroceryContributionRepository $contributions,
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
        $out = [];
        foreach ($this->ingredientsPerMeal($user, $from, $to) as $entry) {
            if ($entry['missing']) {
                unset($entry['missing']);
                $out[] = $entry;
            }
        }

        return $out;
    }

    /**
     * The ingredients of the meals planned from `$from` to `$to` (both days
     * included), one entry per meal and product, soonest meal first (lunch
     * before dinner). `missing` is true when the owner is low on it or out
     * (MAG-590): the resource behind « Pour les recettes » reads this.
     *
     * @return list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string, missing: bool}>
     */
    public function ingredientsPerMeal(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        $meals = $this->mealRepository->findByDateRangeForUser($user, $from, $to);
        usort($meals, static fn ($a, $b) => [$a->getDate(), MealSlot::Lunch === $a->getSlot() ? 0 : 1] <=> [$b->getDate(), MealSlot::Lunch === $b->getSlot() ? 0 : 1]);

        $out = [];
        foreach ($meals as $meal) {
            foreach ($meal->getRecipes() as $recipe) {
                foreach ($recipe->getIngredients() as $line) {
                    $ingredient = $line->getIngredient();
                    $id = (string) $ingredient->getId();
                    $key = $meal->getId().':'.$id;
                    if (isset($out[$key])) {
                        continue;
                    }

                    $out[$key] = [
                        'productId' => $id,
                        'name' => $ingredient->getName(),
                        'date' => $meal->getDate()?->format('Y-m-d') ?? '',
                        'slot' => $meal->getSlot()->value,
                        'mealId' => (string) $meal->getId(),
                        'mealName' => MealTitle::of($meal),
                        'missing' => ProductStockState::InStock !== $ingredient->getStockState(),
                    ];
                }
            }
        }

        return array_values($out);
    }

    /**
     * One contribution per meal and their sum as the line's need. A meal already
     * holding a line of the product elsewhere is left to it.
     */
    public function hold(GroceryItem $item, Product $product, array $meals): void
    {
        $needed = [];
        foreach ($meals as $line) {
            $meal = $this->em->getReference(Meal::class, $line['mealId']);
            foreach ($this->contributions->findByMeal($meal) as $held) {
                if ((string) $held->getGroceryItem()->getProduct()?->getId() === (string) $product->getId()) {
                    continue 2;
                }
            }

            $unit = null === $line['unit'] ? null : Unit::from($line['unit']);
            $needed = Needs::add($needed, $line['quantity'], $unit);

            $contribution = new MealGroceryContribution();
            $contribution->setMeal($meal);
            $contribution->setGroceryItem($item);
            $contribution->setQuantity($line['quantity']);
            $contribution->setUnit($unit);
            $this->em->persist($contribution);
        }

        $item->setNeeded([] === $needed ? null : $needed);
    }

    public function plannedMeals(User $user, Product $product, int $days): array
    {
        $productId = (string) $product->getId();

        return array_map(static fn (Meal $meal) => [
            'date' => $meal->getDate()?->format('Y-m-d') ?? '',
            'slot' => $meal->getSlot()->value,
            'recipes' => array_values(array_map(
                static fn (Recipe $recipe) => $recipe->getName(),
                array_filter(
                    $meal->getRecipes()->toArray(),
                    static fn (Recipe $recipe) => $recipe->getIngredients()->exists(
                        static fn ($_, RecipeIngredient $line) => (string) $line->getIngredient()->getId() === $productId,
                    ),
                ),
            )),
        ], $this->mealRepository->findUpcomingUsingProduct($user, $product, $days));
    }

    public function watchedEntities(): array
    {
        return [Meal::class, Recipe::class];
    }
}
