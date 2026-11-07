<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Health;

use Maggie\Cookbook\Service\CiqualClient;
use Maggie\Cookbook\Service\CiqualNutrients;
use Maggie\Health\Port\CiqualFood;
use Maggie\Health\Port\FoodCatalogInterface;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;

/** Santé's port on the Ciqual foods (MAG-748), through the cookbook's client. */
final class CiqualFoodCatalog implements FoodCatalogInterface
{
    public function __construct(
        private readonly CiqualClient $client,
        private readonly CiqualNutrients $nutrients,
    ) {
    }

    public function food(string $code): ?CiqualFood
    {
        try {
            $food = $this->client->getFood($code);
        } catch (ExceptionInterface $e) {
            throw new \RuntimeException('Ciqual cannot be reached.', 0, $e);
        }
        if (null === $food) {
            return null;
        }

        $macros = $this->nutrients->macrosFromFood($food);

        return new CiqualFood(
            code: $food['alim_code'],
            name: $food['alim_name_fr'],
            kcalPer100g: $macros['kcalPer100g'] ?? null,
            proteinPer100g: $macros['proteinPer100g'] ?? null,
            carbsPer100g: $macros['carbsPer100g'] ?? null,
            fatPer100g: $macros['fatPer100g'] ?? null,
        );
    }
}
