<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Port\MealNutritionInterface;
use Maggie\Health\Port\MealPortion;
use Maggie\Health\Repository\FoodIntakeRepository;

/**
 * The day's calorie balance (MAG-748), as the endpoint and the tool return
 * it: what was eaten, plus the day's planned meals not eaten yet, minus the
 * consolidated expenditure of {@see DailyEnergyReader}. The macros add the
 * eaten and the planned, like the balance. A meal none of whose recipes can
 * be counted plans nothing. No expenditure yet (the health profile is
 * incomplete): no balance.
 */
final class DailyBalanceReader
{
    public const MAX_DAYS = 31;

    public function __construct(
        private readonly FoodIntakeRepository $intakes,
        private readonly MealNutritionInterface $meals,
        private readonly DailyEnergyReader $energy,
    ) {
    }

    /**
     * @return array{date: string, eatenKcal: int, plannedKcal: int, expendedKcal: ?int, balanceKcal: ?int, proteinG: float, carbsG: float, fatG: float}
     *
     * @throws \InvalidArgumentException naming the parameter, for the caller to refuse
     */
    public function day(User $user, string $date): array
    {
        DailyEnergyReader::day('date', $date);

        return $this->range($user, $date, $date)[0];
    }

    /**
     * The day's planned meals not eaten yet.
     *
     * @return list<MealPortion>
     *
     * @throws \InvalidArgumentException
     */
    public function plannedMeals(User $user, string $date): array
    {
        $day = DailyEnergyReader::day('date', $date);
        $eaten = [];
        foreach ($this->intakes->findInRange($user, $day, $day) as $intake) {
            $eaten[(string) $intake->getMealId()] = true;
        }

        return array_values(array_filter(
            $this->meals->plannedOn($user, $day),
            static fn (MealPortion $meal): bool => !isset($eaten[$meal->mealId]),
        ));
    }

    /**
     * @return list<array{date: string, eatenKcal: int, plannedKcal: int, expendedKcal: ?int, balanceKcal: ?int, proteinG: float, carbsG: float, fatG: float}>
     *
     * @throws \InvalidArgumentException
     */
    public function range(User $user, string $from, string $to): array
    {
        $start = DailyEnergyReader::day('from', $from);
        $last = DailyEnergyReader::day('to', $to);
        if ($start > $last) {
            throw new \InvalidArgumentException('from must not be after to.');
        }
        if ($start->diff($last)->days >= self::MAX_DAYS) {
            throw new \InvalidArgumentException('The period is at most '.self::MAX_DAYS.' days.');
        }

        $expended = [];
        foreach ($this->energy->read($user, $from, $to) as $row) {
            $expended[$row['date']] = $row['expendedKcal'];
        }

        $byDay = [];
        $eatenMeals = [];
        foreach ($this->intakes->findInRange($user, $start, $last) as $intake) {
            $byDay[(string) $intake->getDate()?->format('Y-m-d')][] = $intake;
            if (null !== $intake->getMealId()) {
                $eatenMeals[$intake->getMealId()] = true;
            }
        }

        $rows = [];
        for ($day = $start; $day <= $last; $day = $day->modify('+1 day')) {
            $key = $day->format('Y-m-d');
            $eaten = 0;
            $planned = 0;
            $protein = $carbs = $fat = 0.0;
            foreach ($byDay[$key] ?? [] as $intake) {
                $eaten += (int) $intake->getKcal();
                $protein += (float) $intake->getProteinG();
                $carbs += (float) $intake->getCarbsG();
                $fat += (float) $intake->getFatG();
            }
            foreach ($this->meals->plannedOn($user, $day) as $meal) {
                if (isset($eatenMeals[$meal->mealId]) || null === $meal->kcal) {
                    continue;
                }
                $planned += $meal->kcal;
                $protein += $meal->proteinG;
                $carbs += $meal->carbsG;
                $fat += $meal->fatG;
            }
            $spent = $expended[$key] ?? null;

            $rows[] = [
                'date' => $key,
                'eatenKcal' => $eaten,
                'plannedKcal' => $planned,
                'expendedKcal' => $spent,
                'balanceKcal' => null === $spent ? null : $eaten + $planned - $spent,
                'proteinG' => round($protein, 1),
                'carbsG' => round($carbs, 1),
                'fatG' => round($fat, 1),
            ];
        }

        return $rows;
    }
}
