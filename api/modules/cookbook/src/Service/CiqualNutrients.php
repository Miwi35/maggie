<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;

/**
 * The four macros Ciqual gives per 100 g, and how an ingredient linked to a
 * Ciqual code gets them (MAG-647): the code alone computes nothing. Its
 * micronutrients come with them (MAG-648), {@see Micronutrients}.
 */
class CiqualNutrients
{
    public const MACRO_FIELDS = ['kcalPer100g', 'proteinPer100g', 'carbsPer100g', 'fatPer100g'];

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
     * Its micronutrients too, when it has none yet: they only ever come from Ciqual, all at once.
     * Ciqual unreachable or the code unknown leaves the ingredient as it is.
     *
     * @param list<string> $except macro fields not to fill (the ones the caller has just emptied on purpose)
     *
     * @return bool whether something was filled
     */
    public function fillMissing(Ingredient $ingredient, array $except = []): bool
    {
        $code = $ingredient->getCiqualAlimCode();
        $lacksMicronutrients = null === $ingredient->getMicronutrientsPer100g();
        if (null === $code || '' === $code || (!$this->lacksMacros($ingredient, $except) && !$lacksMicronutrients)) {
            return false;
        }

        $food = $this->food($code);
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
        if ($lacksMicronutrients) {
            $ingredient->setMicronutrientsPer100g(Micronutrients::fromFood($food));
            $filled = true;
        }

        return $filled;
    }

    /**
     * The micronutrients per 100 g of a Ciqual food, null when Ciqual is unreachable or the code unknown.
     *
     * @return array<string, float>|null
     */
    public function micronutrientsOf(string $code): ?array
    {
        $food = $this->food($code);

        return null === $food ? null : Micronutrients::fromFood($food);
    }

    /** @return array{nutrients: list<array{const_code: string, value: ?float}>}|null */
    private function food(string $code): ?array
    {
        try {
            return $this->ciqualClient->getFood($code);
        } catch (\Throwable) {
            return null;
        }
    }

    /** @param list<string> $except */
    private function lacksMacros(Ingredient $ingredient, array $except): bool
    {
        foreach (array_diff(self::MACRO_FIELDS, $except) as $field) {
            if (null === $ingredient->{'get'.ucfirst($field)}()) {
                return true;
            }
        }

        return false;
    }
}
