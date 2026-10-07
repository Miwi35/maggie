<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
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

        if (null !== $command->name) {
            $ingredient->setName($command->name);
        }
        if (null !== $command->category) {
            $ingredient->setCategory(ProductCategory::from($command->category));
        }
        if (null !== $command->defaultUnit) {
            $ingredient->setDefaultUnit(Unit::from($command->defaultUnit));
        } elseif ($command->clears('defaultUnit')) {
            $ingredient->setDefaultUnit(null);
        }
        if (null !== $command->ciqualAlimCode) {
            $ingredient->setCiqualAlimCode($command->ciqualAlimCode);
        } elseif ($command->clears('ciqualAlimCode')) {
            $ingredient->setCiqualAlimCode(null);
        }
        if (null !== $command->kcalPer100g) {
            $ingredient->setKcalPer100g($command->kcalPer100g);
        } elseif ($command->clears('kcalPer100g')) {
            $ingredient->setKcalPer100g(null);
        }
        if (null !== $command->proteinPer100g) {
            $ingredient->setProteinPer100g($command->proteinPer100g);
        } elseif ($command->clears('proteinPer100g')) {
            $ingredient->setProteinPer100g(null);
        }
        if (null !== $command->carbsPer100g) {
            $ingredient->setCarbsPer100g($command->carbsPer100g);
        } elseif ($command->clears('carbsPer100g')) {
            $ingredient->setCarbsPer100g(null);
        }
        if (null !== $command->fatPer100g) {
            $ingredient->setFatPer100g($command->fatPer100g);
        } elseif ($command->clears('fatPer100g')) {
            $ingredient->setFatPer100g(null);
        }
        if (null !== $command->packagingUnit) {
            $ingredient->setPackagingUnit(Unit::from($command->packagingUnit));
        } elseif ($command->clears('packagingUnit')) {
            $ingredient->setPackagingUnit(null);
        }
        if (null !== $command->packagingSize) {
            $ingredient->setPackagingSize($command->packagingSize);
        } elseif ($command->clears('packagingSize')) {
            $ingredient->setPackagingSize(null);
        }
        if (null !== $command->packagingSizeUnit) {
            $ingredient->setPackagingSizeUnit(Unit::from($command->packagingSizeUnit));
        } elseif ($command->clears('packagingSizeUnit')) {
            $ingredient->setPackagingSizeUnit(null);
        }
        $ingredient->assertPackagingIsConsistent();

        $this->updateProduct->execute($ingredient);

        return $ingredient;
    }
}
