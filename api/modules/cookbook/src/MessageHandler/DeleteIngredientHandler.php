<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteIngredientCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Grocery\UseCase\DeleteProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteIngredientHandler
{
    public function __construct(
        private readonly DeleteProduct $deleteProduct,
        private readonly IngredientRepository $ingredientRepository,
    ) {
    }

    public function __invoke(DeleteIngredientCommand $command): void
    {
        $ingredient = $this->ingredientRepository->find($command->ingredientId)
            ?? throw new \DomainException("Ingredient not found: {$command->ingredientId}");

        $this->deleteProduct->execute($ingredient);
    }
}
