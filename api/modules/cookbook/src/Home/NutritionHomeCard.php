<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Home;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Cookbook\Service\MealTitle;
use Maggie\Cookbook\Service\MissingIngredients;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeDay;
use Symfony\Component\Clock\ClockInterface;

/**
 * The Nutrition card of the home page (MAG-564): the meals of the day the
 * selector shows, lunch then dinner, each with its recipes, its guests and the
 * ingredients it lacks. The week is Nutrition › Repas; the day's kcal are Santé's.
 */
class NutritionHomeCard implements HomeCardProviderInterface
{
    public function __construct(
        private readonly MealRepository $meals,
        private readonly MealGuests $guests,
        private readonly ClockInterface $clock,
    ) {
    }

    public function module(): string
    {
        return 'nutrition';
    }

    /**
     * Both slots are always present, an empty one with no meal: the card draws « — ».
     *
     * @return array{
     *     date: string,
     *     slots: list<array{slot: string, meals: list<array{
     *         id: string,
     *         title: string,
     *         guests: int,
     *         recipes: list<array{id: string, name: string}>,
     *         missing: list<array{id: string, name: string}>,
     *     }>}>,
     * }
     */
    public function state(User $user, HomeDay $day): array
    {
        $date = $day->date($this->clock);
        $bySlot = [MealSlot::Lunch->value => [], MealSlot::Dinner->value => []];

        foreach ($this->meals->findByDateRangeForUser($user, $date, $date) as $meal) {
            $bySlot[$meal->getSlot()->value][] = $this->line($meal, $user);
        }

        return [
            'date' => $date->format('Y-m-d'),
            'slots' => array_map(
                static fn (string $slot): array => ['slot' => $slot, 'meals' => $bySlot[$slot]],
                array_keys($bySlot),
            ),
        ];
    }

    /** @return array{id: string, title: string, guests: int, recipes: list<array{id: string, name: string}>, missing: list<array{id: string, name: string}>} */
    private function line(Meal $meal, User $user): array
    {
        return [
            'id' => (string) $meal->getId(),
            'title' => MealTitle::of($meal),
            'guests' => $this->guests->of($meal, $user),
            'recipes' => $meal->getRecipes()->map(static fn ($recipe): array => [
                'id' => (string) $recipe->getId(),
                'name' => $recipe->getName(),
            ])->getValues(),
            'missing' => array_map(
                static fn ($ingredient): array => ['id' => (string) $ingredient->getId(), 'name' => $ingredient->getName()],
                MissingIngredients::ofMeal($meal),
            ),
        ];
    }
}
