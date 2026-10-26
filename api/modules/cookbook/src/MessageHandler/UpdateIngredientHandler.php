<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\Service\CiqualNutrients;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\UseCase\UpdateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateIngredientHandler
{
    public function __construct(
        private readonly UpdateProduct $updateProduct,
        private readonly CiqualNutrients $ciqualNutrients,
        private readonly IngredientRepository $ingredientRepository,
        private readonly StoreRepository $storeRepository,
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
        // The request names a store by IRI and API Platform rebuilds it from the index: swap it for the managed row.
        if (null !== $command->preferredStoreId) {
            $ingredient->setPreferredStore($this->ownedStore($command->preferredStoreId, $ingredient));
        } elseif ($command->clears('preferredStore')) {
            $ingredient->setPreferredStore(null);
        }
        if (null !== $command->fallbackStoreId) {
            $ingredient->setFallbackStore($this->ownedStore($command->fallbackStoreId, $ingredient));
        } elseif ($command->clears('fallbackStore')) {
            $ingredient->setFallbackStore(null);
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
        if (null !== $command->pieceWeightGrams) {
            $ingredient->setPieceWeightGrams($command->pieceWeightGrams);
        } elseif ($command->clears('pieceWeightGrams')) {
            $ingredient->setPieceWeightGrams(null);
        }
        // The Ciqual code alone computes nothing: the macros come with it, the ones already there stay.
        $this->ciqualNutrients->fillMissing($ingredient, $command->clearFields);
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
        if (null !== $command->stockState) {
            $ingredient->setStockState(Product::parseStockState($command->stockState));
        }
        if (null !== $command->restockQuantity) {
            $ingredient->setRestockQuantity($command->restockQuantity);
        } elseif ($command->clears('restockQuantity')) {
            $ingredient->setRestockQuantity(null);
        }
        if (null !== $command->autoRestock) {
            $ingredient->setAutoRestock($command->autoRestock);
        }
        $ingredient->assertRestockQuantityIsValid();
        $ingredient->assertSeasonMonthsAreValid();

        $this->updateProduct->execute($ingredient);

        return $ingredient;
    }

    private function ownedStore(string $storeId, Ingredient $ingredient): Store
    {
        $store = $this->storeRepository->find($storeId);
        if (null === $store || (string) $store->getUser()->getId() !== (string) $ingredient->getUser()->getId()) {
            throw new \DomainException("Store not found: {$storeId}");
        }

        return $store;
    }
}
