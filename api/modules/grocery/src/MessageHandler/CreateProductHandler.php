<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\CreateProductCommand;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\UseCase\CreateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateProductHandler
{
    public function __construct(
        private readonly CreateProduct $createProduct,
        private readonly UserRepository $userRepository,
        private readonly StoreRepository $storeRepository,
    ) {
    }

    public function __invoke(CreateProductCommand $command): Product
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $product = new Product();
        $product->setUser($user);
        $product->setName($command->name);
        $product->setCategory(ProductCategory::from($command->category));

        if (null !== $command->defaultUnit) {
            $product->setDefaultUnit(Unit::from($command->defaultUnit));
        }

        if (null !== $command->preferredStoreId) {
            $product->setPreferredStore($this->ownedStore($command->preferredStoreId, $command->userId));
        }
        if (null !== $command->fallbackStoreId) {
            $product->setFallbackStore($this->ownedStore($command->fallbackStoreId, $command->userId));
        }
        $product->setShelfLifeDays($command->shelfLifeDays);
        $product->setPackagingUnit(null !== $command->packagingUnit ? Unit::from($command->packagingUnit) : null);
        $product->setPackagingSize($command->packagingSize);
        $product->setPackagingSizeUnit(null !== $command->packagingSizeUnit ? Unit::from($command->packagingSizeUnit) : null);
        $product->assertPackagingIsConsistent();

        return $this->createProduct->execute($product);
    }

    private function ownedStore(string $storeId, string $userId): Store
    {
        $store = $this->storeRepository->find($storeId);
        if (null === $store || (string) $store->getUser()->getId() !== $userId) {
            throw new \DomainException("Store not found: {$storeId}");
        }

        return $store;
    }
}
