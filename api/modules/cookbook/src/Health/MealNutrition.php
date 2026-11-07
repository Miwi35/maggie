<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Health;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealTitle;
use Maggie\Cookbook\Service\RecipeNutrition;
use Maggie\Core\Entity\User;
use Maggie\Health\Port\MealNutritionInterface;
use Maggie\Health\Port\MealPortion;

/**
 * Santé's port on the planned meals (MAG-748): one person eats one serving of
 * each recipe, counted as the recipe sheet counts it. A meal none of whose
 * recipes can be counted has no kcal.
 */
final class MealNutrition implements MealNutritionInterface
{
    private const SLOT_ORDER = [MealSlot::Lunch->value => 0, MealSlot::Dinner->value => 1];

    public function __construct(
        private readonly MealRepository $meals,
    ) {
    }

    public function plannedOn(User $user, \DateTimeImmutable $day): array
    {
        $date = new \DateTimeImmutable($day->format('Y-m-d'), new \DateTimeZone('UTC'));

        $portions = array_map(self::portion(...), $this->meals->findByDateRangeForUser($user, $date, $date));
        usort($portions, static fn (MealPortion $a, MealPortion $b): int => self::SLOT_ORDER[$a->slot] <=> self::SLOT_ORDER[$b->slot]);

        return $portions;
    }

    public function ofMeal(User $user, string $mealId): ?MealPortion
    {
        $meal = $this->meals->findOneForUser($mealId, $user);

        return null === $meal ? null : self::portion($meal);
    }

    private static function portion(Meal $meal): MealPortion
    {
        $kcal = null;
        $protein = $carbs = $fat = 0.0;
        foreach ($meal->getRecipes() as $recipe) {
            $serving = RecipeNutrition::perServing($recipe);
            if (null === $serving) {
                continue;
            }
            $kcal = ($kcal ?? 0) + $serving['kcal'];
            $protein += $serving['proteinG'];
            $carbs += $serving['carbsG'];
            $fat += $serving['fatG'];
        }

        return new MealPortion(
            mealId: (string) $meal->getId(),
            date: $meal->getDate()?->format('Y-m-d') ?? '',
            slot: $meal->getSlot()->value,
            label: MealTitle::of($meal),
            kcal: $kcal,
            proteinG: round($protein, 1),
            carbsG: round($carbs, 1),
            fatG: round($fat, 1),
        );
    }
}
