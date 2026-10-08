<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\UseCase\CreateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateIngredientHandler
{
    public function __construct(
        private readonly CreateProduct $createProduct,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateIngredientCommand $command): Ingredient
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $ingredient = new Ingredient();
        $ingredient->setUser($user);
        $ingredient->setName($command->name);
        $ingredient->setCategory(ProductCategory::from($command->category));

        if (null !== $command->defaultUnit) {
            $ingredient->setDefaultUnit(Unit::from($command->defaultUnit));
        }

        if (null !== $command->ciqualAlimCode) {
            $ingredient->setCiqualAlimCode($command->ciqualAlimCode);
        }
        $ingredient->setKcalPer100g($command->kcalPer100g);
        $ingredient->setProteinPer100g($command->proteinPer100g);
        $ingredient->setCarbsPer100g($command->carbsPer100g);
        $ingredient->setFatPer100g($command->fatPer100g);
        $ingredient->setPackagingUnit(null !== $command->packagingUnit ? Unit::from($command->packagingUnit) : null);
        $ingredient->setPackagingSize($command->packagingSize);
        $ingredient->setPackagingSizeUnit(null !== $command->packagingSizeUnit ? Unit::from($command->packagingSizeUnit) : null);
        $ingredient->assertPackagingIsConsistent();
        if (null !== $command->stockState) {
            $ingredient->setStockState(Product::parseStockState($command->stockState));
        }
        $ingredient->setRestockQuantity($command->restockQuantity);
        $ingredient->setAutoRestock($command->autoRestock);
        $ingredient->assertRestockQuantityIsValid();

        $this->createProduct->execute($ingredient);

        return $ingredient;
    }
}
