<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Exception\InvalidFoodIntakeException;

/**
 * What a food intake is held to whichever door it comes through (API, MCP).
 * Every refusal is an InvalidFoodIntakeException naming the field: a 400 for
 * the API, an error for the tool. Nothing is deduced.
 */
final class FoodIntakeRules
{
    public const MAX_KCAL = 10_000;

    public const MAX_MACRO_G = 2_000;

    public const MAX_QUANTITY_G = 10_000;

    /** ISO 8601 with a time and an offset, e.g. 2026-11-07T12:30:00+01:00: a date alone or a time without offset is refused, never completed. */
    public function eatenAt(string $value): \DateTimeImmutable
    {
        $hint = 'must be ISO 8601 with time and offset, e.g. 2026-11-07T12:30:00+01:00.';
        if (1 !== preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/', $value)) {
            throw new InvalidFoodIntakeException('eatenAt', $hint);
        }

        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw new InvalidFoodIntakeException('eatenAt', $hint);
        }
    }

    /** The local day (Paris, like every day the user gives) an intake eaten then counts for. */
    public function dayOf(\DateTimeImmutable $eatenAt): \DateTimeImmutable
    {
        $local = $eatenAt->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE));

        return new \DateTimeImmutable($local->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    /** The figures are checked as they will be stored. */
    public function assert(?string $label, ?int $kcal, ?float $proteinG, ?float $carbsG, ?float $fatG, ?float $quantityGrams, ?string $ciqualCode = null): void
    {
        if (null === $label || '' === trim($label)) {
            throw new InvalidFoodIntakeException('label', 'say what was eaten, e.g. « Salade César ».');
        }
        if (mb_strlen($label) > 255) {
            throw new InvalidFoodIntakeException('label', 'must be at most 255 characters.');
        }
        if (null === $kcal) {
            throw new InvalidFoodIntakeException('kcal', 'is required: give kcal, or a ciqualCode with quantityGrams.');
        }
        if ($kcal < 0 || $kcal > self::MAX_KCAL) {
            throw new InvalidFoodIntakeException('kcal', 'is an integer from 0 to '.self::MAX_KCAL.'.');
        }
        foreach (['proteinG' => $proteinG, 'carbsG' => $carbsG, 'fatG' => $fatG] as $field => $grams) {
            if (null !== $grams && ($grams < 0 || $grams > self::MAX_MACRO_G)) {
                throw new InvalidFoodIntakeException($field, 'is in grams, from 0 to '.self::MAX_MACRO_G.'.');
            }
        }
        if (null !== $quantityGrams && ($quantityGrams <= 0 || $quantityGrams > self::MAX_QUANTITY_G)) {
            throw new InvalidFoodIntakeException('quantityGrams', 'is in grams, more than 0 and at most '.self::MAX_QUANTITY_G.'.');
        }
        if (null !== $ciqualCode) {
            $this->ciqualCode($ciqualCode);
        }
    }

    public function ciqualCode(string $code): void
    {
        if (1 !== preg_match('/^\d{1,16}$/', $code)) {
            throw new InvalidFoodIntakeException('ciqualCode', 'is a Ciqual food code, digits only, e.g. 20020.');
        }
    }
}
