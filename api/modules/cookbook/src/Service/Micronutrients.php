<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Recipe;

/**
 * The 23 micronutrients of the recipe sheet (MAG-648), in its order: the Ciqual code each is read
 * from, its unit per 100 g, and the daily reference a serving is a share of.
 *
 * The references are fixed and the same for everyone, until personal intakes replace them (Santé ›
 * Configuration):
 * - vitamins and minerals: the nutrient reference values of Regulation (EU) No 1169/2011, Annex XIII,
 *   part A, point 1 — https://eur-lex.europa.eu/eli/reg/2011/1169/oj;
 * - fatty acids: ANSES, « Actualisation des apports nutritionnels conseillés pour les acides gras »
 *   (saisine 2006-SA-0359, 2011), adult: linoleic 4 % and alpha-linolenic 1 % of the energy intake,
 *   here the 2 000 kcal of the same Annex XIII, part B, at 9 kcal per gram; DHA 250 mg, and EPA the
 *   250 mg left of the 500 mg ANSES sets for EPA + DHA together.
 */
final class Micronutrients
{
    public const VITAMINS = 'vitamins';
    public const MINERALS = 'minerals';
    public const FATTY_ACIDS = 'fattyAcids';

    private const ENERGY_REFERENCE_KCAL = 2000.0;
    private const KCAL_PER_GRAM_OF_FAT = 9.0;

    /** key => [Ciqual const_code, label, group, unit] */
    private const CATALOGUE = [
        'vitaminA' => ['51104', 'Vitamine A', self::VITAMINS, 'µg'],
        'vitaminB1' => ['56100', 'Vitamine B1', self::VITAMINS, 'mg'],
        'vitaminB2' => ['56200', 'Vitamine B2', self::VITAMINS, 'mg'],
        'vitaminB3' => ['56310', 'Vitamine B3', self::VITAMINS, 'mg'],
        'vitaminB5' => ['56400', 'Vitamine B5', self::VITAMINS, 'mg'],
        'vitaminB6' => ['56500', 'Vitamine B6', self::VITAMINS, 'mg'],
        'vitaminB12' => ['56600', 'Vitamine B12', self::VITAMINS, 'µg'],
        'vitaminC' => ['55100', 'Vitamine C', self::VITAMINS, 'mg'],
        'vitaminD' => ['52100', 'Vitamine D', self::VITAMINS, 'µg'],
        'calcium' => ['10200', 'Calcium', self::MINERALS, 'mg'],
        'iron' => ['10260', 'Fer', self::MINERALS, 'mg'],
        'magnesium' => ['10120', 'Magnésium', self::MINERALS, 'mg'],
        'phosphorus' => ['10150', 'Phosphore', self::MINERALS, 'mg'],
        'potassium' => ['10190', 'Potassium', self::MINERALS, 'mg'],
        'zinc' => ['10300', 'Zinc', self::MINERALS, 'mg'],
        'copper' => ['10290', 'Cuivre', self::MINERALS, 'mg'],
        'manganese' => ['10251', 'Manganèse', self::MINERALS, 'mg'],
        'selenium' => ['10340', 'Sélénium', self::MINERALS, 'µg'],
        'iodine' => ['10530', 'Iode', self::MINERALS, 'µg'],
        'omega3Ala' => ['41833', 'Oméga-3 ALA', self::FATTY_ACIDS, 'g'],
        'omega3Epa' => ['42053', 'Oméga-3 EPA', self::FATTY_ACIDS, 'g'],
        'omega3Dha' => ['42263', 'Oméga-3 DHA', self::FATTY_ACIDS, 'g'],
        'omega6' => ['41826', 'Oméga-6', self::FATTY_ACIDS, 'g'],
    ];

    /** @return list<string> the keys, in the order of the sheet */
    public static function keys(): array
    {
        return array_keys(self::CATALOGUE);
    }

    /**
     * The daily reference of a micronutrient, in its unit; null for one without a reference (shown as an amount alone).
     */
    public static function reference(string $key): ?float
    {
        return match ($key) {
            // Regulation (EU) No 1169/2011, Annex XIII, part A, point 1.
            'vitaminA' => 800.0,
            'vitaminB1' => 1.1,
            'vitaminB2' => 1.4,
            'vitaminB3' => 16.0,
            'vitaminB5' => 6.0,
            'vitaminB6' => 1.4,
            'vitaminB12' => 2.5,
            'vitaminC' => 80.0,
            'vitaminD' => 5.0,
            'calcium' => 800.0,
            'iron' => 14.0,
            'magnesium' => 375.0,
            'phosphorus' => 700.0,
            'potassium' => 2000.0,
            'zinc' => 10.0,
            'copper' => 1.0,
            'manganese' => 2.0,
            'selenium' => 55.0,
            'iodine' => 150.0,
            // ANSES 2011, adult.
            'omega3Ala' => 0.01 * self::ENERGY_REFERENCE_KCAL / self::KCAL_PER_GRAM_OF_FAT,
            'omega3Epa' => 0.25,
            'omega3Dha' => 0.25,
            'omega6' => 0.04 * self::ENERGY_REFERENCE_KCAL / self::KCAL_PER_GRAM_OF_FAT,
            default => null,
        };
    }

    /** The whole share of the reference an amount is, null without a reference. */
    public static function percentOfReference(string $key, float $amount): ?int
    {
        $reference = self::reference($key);

        return null === $reference ? null : (int) round($amount * 100 / $reference);
    }

    /**
     * @param array{nutrients: list<array{const_code: string, value: ?float}>} $foodData
     *
     * @return array<string, float> key => value per 100 g, only what Ciqual knows
     */
    public static function fromFood(array $foodData): array
    {
        $wanted = array_flip(self::keys());
        foreach ($foodData['nutrients'] as $nutrient) {
            $key = self::keyOfCode($nutrient['const_code']);
            if (null !== $key && null !== $nutrient['value']) {
                $wanted[$key] = (float) $nutrient['value'];
            }
        }

        // In the order of the sheet, without what Ciqual left unknown.
        return array_filter($wanted, \is_float(...));
    }

    private static function keyOfCode(string $code): ?string
    {
        foreach (self::CATALOGUE as $key => [$ciqualCode]) {
            if ($ciqualCode === $code) {
                return $key;
            }
        }

        return null;
    }

    /**
     * What one serving brings: the lines counted by weight, as for the macros (MAG-647), summed and
     * divided by the servings. A micronutrient no counted line gives is left out: absent is not 0.
     *
     * @return list<array{key: string, label: string, group: string, amount: float, unit: string, referencePercent: ?int}>
     */
    public static function perServing(Recipe $recipe): array
    {
        $servings = $recipe->getServings();
        if ($servings <= 0) {
            return [];
        }

        $totals = [];
        foreach ($recipe->getIngredients() as $line) {
            $grams = $line->grams();
            $values = $line->getIngredient()->getMicronutrientsPer100g();
            if (null === $grams || null === $values) {
                continue;
            }
            foreach ($values as $key => $per100g) {
                if (isset(self::CATALOGUE[$key])) {
                    $totals[$key] = ($totals[$key] ?? 0.0) + $grams / 100 * $per100g;
                }
            }
        }

        $perServing = [];
        foreach (self::CATALOGUE as $key => [, $label, $group, $unit]) {
            if (!isset($totals[$key])) {
                continue;
            }
            $amount = $totals[$key] / $servings;
            $perServing[] = [
                'key' => $key,
                'label' => $label,
                'group' => $group,
                'amount' => round($amount, 3),
                'unit' => $unit,
                'referencePercent' => self::percentOfReference($key, $amount),
            ];
        }

        return $perServing;
    }
}
