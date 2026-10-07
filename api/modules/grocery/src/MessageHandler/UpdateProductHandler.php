<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\UseCase\UpdateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateProductHandler
{
    public function __construct(
        private readonly UpdateProduct $updateProduct,
        private readonly ProductRepository $productRepository,
        private readonly StoreRepository $storeRepository,
    ) {
    }

    public function __invoke(UpdateProductCommand $command): Product
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        if (null !== $command->name) {
            $product->setName($command->name);
        }
        if (null !== $command->category) {
            $product->setCategory(ProductCategory::from($command->category));
        }
        if (null !== $command->defaultUnit) {
            $product->setDefaultUnit(Unit::from($command->defaultUnit));
        } elseif ($command->clears('defaultUnit')) {
            $product->setDefaultUnit(null);
        }
        if (null !== $command->preferredStoreId) {
            $product->setPreferredStore($this->ownedStore($command->preferredStoreId, $product));
        } elseif ($command->clears('preferredStore')) {
            $product->setPreferredStore(null);
        }
        if (null !== $command->fallbackStoreId) {
            $product->setFallbackStore($this->ownedStore($command->fallbackStoreId, $product));
        } elseif ($command->clears('fallbackStore')) {
            $product->setFallbackStore(null);
        }
        if (null !== $command->shelfLifeDays) {
            $product->setShelfLifeDays($command->shelfLifeDays);
        } elseif ($command->clears('shelfLifeDays')) {
            $product->setShelfLifeDays(null);
        }
        if (null !== $command->packagingUnit) {
            $product->setPackagingUnit(Unit::from($command->packagingUnit));
        } elseif ($command->clears('packagingUnit')) {
            $product->setPackagingUnit(null);
        }
        if (null !== $command->packagingSize) {
            $product->setPackagingSize($command->packagingSize);
        } elseif ($command->clears('packagingSize')) {
            $product->setPackagingSize(null);
        }
        if (null !== $command->packagingSizeUnit) {
            $product->setPackagingSizeUnit(Unit::from($command->packagingSizeUnit));
        } elseif ($command->clears('packagingSizeUnit')) {
            $product->setPackagingSizeUnit(null);
        }
        $product->assertPackagingIsConsistent();

        return $this->updateProduct->execute($product);
    }

    private function ownedStore(string $storeId, Product $product): Store
    {
        $store = $this->storeRepository->find($storeId);
        if (null === $store || (string) $store->getUser()->getId() !== (string) $product->getUser()->getId()) {
            throw new \DomainException("Store not found: {$storeId}");
        }

        return $store;
    }
}
