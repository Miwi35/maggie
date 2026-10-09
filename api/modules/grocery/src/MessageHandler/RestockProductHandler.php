<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\RestockProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\UseCase\RestockProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Returns the list so the Mercure and Elasticsearch middlewares publish and
 * reindex it: the open screens get the line without reloading.
 */
#[AsMessageHandler]
class RestockProductHandler
{
    public function __construct(
        private readonly RestockProduct $restockProduct,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(RestockProductCommand $command): ?GroceryList
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        return $this->restockProduct->execute($product);
    }
}
