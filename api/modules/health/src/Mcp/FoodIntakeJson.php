<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp;

use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Port\MealPortion;

/** What the MCP tools say about a food intake: the API's shape plus the French source label. */
final class FoodIntakeJson
{
    /** @return array<string, mixed> */
    public static function of(FoodIntake $intake): array
    {
        return [
            'id' => (string) $intake->getId(),
            'date' => $intake->getDate()?->format('Y-m-d'),
            'eatenAt' => $intake->getEatenAt()?->format(\DATE_ATOM),
            'mealId' => $intake->getMealId(),
            'ciqualCode' => $intake->getCiqualCode(),
            'label' => $intake->getLabel(),
            'quantityGrams' => $intake->getQuantityGrams(),
            'kcal' => $intake->getKcal(),
            'proteinG' => $intake->getProteinG(),
            'carbsG' => $intake->getCarbsG(),
            'fatG' => $intake->getFatG(),
            'source' => $intake->getSource()->value,
            'sourceLabel' => $intake->getSource()->label(),
        ];
    }

    /**
     * @param list<FoodIntake> $intakes
     *
     * @return list<array<string, mixed>>
     */
    public static function list(array $intakes): array
    {
        return array_map(self::of(...), $intakes);
    }

    /** @return array{mealId: string, slot: string, label: string, kcal: ?int, proteinG: float, carbsG: float, fatG: float} */
    public static function planned(MealPortion $meal): array
    {
        return [
            'mealId' => $meal->mealId,
            'slot' => $meal->slot,
            'label' => $meal->label,
            'kcal' => $meal->kcal,
            'proteinG' => $meal->proteinG,
            'carbsG' => $meal->carbsG,
            'fatG' => $meal->fatG,
        ];
    }
}
