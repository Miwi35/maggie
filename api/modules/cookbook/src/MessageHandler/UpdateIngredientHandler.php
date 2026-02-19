<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Enum\ProductCategory;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\UseCase\UpdateProduct;
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
        }

        return $this->updateProduct->execute($ingredient);
    }
}
