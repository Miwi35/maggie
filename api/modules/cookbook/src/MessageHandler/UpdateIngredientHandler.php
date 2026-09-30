<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Grocery\UseCase\UpdateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateIngredientHandler
{
    public function __construct(
        private readonly UpdateProduct $updateProduct,
        private readonly IngredientRepository $ingredientRepository,
    ) {
    }

    public function __invoke(UpdateIngredientCommand $command): Ingredient
    {
        $ingredient = $this->ingredientRepository->find($command->ingredientId)
            ?? throw new \DomainException("Ingredient not found: {$command->ingredientId}");

        if ($command->name !== null) {
            $ingredient->setName($command->name);
        }
        if ($command->category !== null) {
            $ingredient->setCategory(ProductCategory::from($command->category));
        }
        if ($command->defaultUnit !== null) {
            $ingredient->setDefaultUnit(Unit::from($command->defaultUnit));
        } elseif ($command->clears('defaultUnit')) {
            $ingredient->setDefaultUnit(null);
        }
        if ($command->ciqualAlimCode !== null) {
            $ingredient->setCiqualAlimCode($command->ciqualAlimCode);
        } elseif ($command->clears('ciqualAlimCode')) {
            $ingredient->setCiqualAlimCode(null);
        }
        if ($command->kcalPer100g !== null) {
            $ingredient->setKcalPer100g($command->kcalPer100g);
        } elseif ($command->clears('kcalPer100g')) {
            $ingredient->setKcalPer100g(null);
        }
        if ($command->proteinPer100g !== null) {
            $ingredient->setProteinPer100g($command->proteinPer100g);
        } elseif ($command->clears('proteinPer100g')) {
            $ingredient->setProteinPer100g(null);
        }
        if ($command->carbsPer100g !== null) {
            $ingredient->setCarbsPer100g($command->carbsPer100g);
        } elseif ($command->clears('carbsPer100g')) {
            $ingredient->setCarbsPer100g(null);
        }
        if ($command->fatPer100g !== null) {
            $ingredient->setFatPer100g($command->fatPer100g);
        } elseif ($command->clears('fatPer100g')) {
            $ingredient->setFatPer100g(null);
        }

        return $this->updateProduct->execute($ingredient);
    }
}
