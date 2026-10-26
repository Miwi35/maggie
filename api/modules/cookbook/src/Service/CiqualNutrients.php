<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;

/**
 * The four macros Ciqual gives per 100 g, and how an ingredient linked to a
 * Ciqual code gets them (MAG-647): the code alone computes nothing.
 */
class CiqualNutrients
{
    private const NUTRIENT_CODES = [
        '328' => 'kcalPer100g',
        '25000' => 'proteinPer100g',
        '31000' => 'carbsPer100g',
        '40000' => 'fatPer100g',
    ];

    public function __construct(
        private readonly CiqualClient $ciqualClient,
    ) {
    }

    /**
     * @param array{nutrients: list<array{const_code: string, value: ?float}>} $foodData
     *
     * @return array<string, float> field name => value per 100 g, only what Ciqual knows
     */
    public function macrosFromFood(array $foodData): array
    {
        $macros = [];
        foreach ($foodData['nutrients'] as $nutrient) {
            $field = self::NUTRIENT_CODES[$nutrient['const_code']] ?? null;
            if (null !== $field && null !== $nutrient['value']) {
                $macros[$field] = (float) $nutrient['value'];
            }
        }

        return $macros;
    }

    /**
     * Fills the macros the ingredient lacks from its Ciqual code; a value already there is never replaced.
     * Ciqual unreachable or the code unknown leaves the ingredient as it is.
     *
     * @param list<string> $except fields not to fill (the ones the caller has just emptied on purpose)
     *
     * @return bool whether something was filled
     */
    public function fillMissing(Ingredient $ingredient, array $except = []): bool
    {
        $code = $ingredient->getCiqualAlimCode();
        if (null === $code || '' === $code || !$this->lacksMacros($ingredient)) {
            return false;
        }

        try {
            $food = $this->ciqualClient->getFood($code);
        } catch (\Throwable) {
            return false;
        }
        if (null === $food) {
            return false;
        }

        $filled = false;
        foreach ($this->macrosFromFood($food) as $field => $value) {
            $getter = 'get'.ucfirst($field);
            if (!\in_array($field, $except, true) && null === $ingredient->$getter()) {
                $ingredient->{'set'.ucfirst($field)}($value);
                $filled = true;
            }
        }

        return $filled;
    }

    private function lacksMacros(Ingredient $ingredient): bool
    {
        return null === $ingredient->getKcalPer100g()
            || null === $ingredient->getProteinPer100g()
            || null === $ingredient->getCarbsPer100g()
            || null === $ingredient->getFatPer100g();
    }
}
