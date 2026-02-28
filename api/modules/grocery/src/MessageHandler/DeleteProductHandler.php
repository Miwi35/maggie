<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Message\DeleteProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\UseCase\DeleteProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteProductHandler
{
    public function __construct(
        private readonly DeleteProduct $deleteProduct,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(DeleteProductCommand $command): void
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        $this->deleteProduct->execute($product);
    }
}
