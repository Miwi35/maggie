<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\UseCase\UpdateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateProductHandler
{
    public function __construct(
        private readonly UpdateProduct $updateProduct,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(UpdateProductCommand $command): Product
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        if ($command->name !== null) {
            $product->setName($command->name);
        }
        if ($command->category !== null) {
            $product->setCategory(ProductCategory::from($command->category));
        }
        if ($command->defaultUnit !== null) {
            $product->setDefaultUnit(Unit::from($command->defaultUnit));
        } elseif ($command->clears('defaultUnit')) {
            $product->setDefaultUnit(null);
        }

        return $this->updateProduct->execute($product);
    }
}
