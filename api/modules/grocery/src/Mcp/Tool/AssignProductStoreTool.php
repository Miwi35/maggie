<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'assign_product_store', description: 'Set the preferred/fallback store and shelf life for a product. shelfLifeDays indicates how many days the product stays fresh (null = non-perishable).')]
class AssignProductStoreTool
{
    public function __construct(
        private readonly ProductRepository $productRepository,
        private readonly StoreRepository $storeRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(
        string $productId,
        ?string $preferredStoreId = null,
        ?string $fallbackStoreId = null,
        ?int $shelfLifeDays = null,
    ): string {
        $product = $this->productRepository->find($productId);
        if (null === $product) {
            return json_encode(['error' => "Product not found: {$productId}"], JSON_THROW_ON_ERROR);
        }

        if (null !== $preferredStoreId) {
            $store = $this->storeRepository->find($preferredStoreId);
            if (null === $store) {
                return json_encode(['error' => "Store not found: {$preferredStoreId}"], JSON_THROW_ON_ERROR);
            }
            $product->setPreferredStore($store);
        }

        if (null !== $fallbackStoreId) {
            $store = $this->storeRepository->find($fallbackStoreId);
            if (null === $store) {
                return json_encode(['error' => "Store not found: {$fallbackStoreId}"], JSON_THROW_ON_ERROR);
            }
            $product->setFallbackStore($store);
        }

        if (null !== $shelfLifeDays) {
            $product->setShelfLifeDays($shelfLifeDays);
        }

        $this->em->flush();

        return json_encode([
            'success' => true,
            'product' => [
                'id' => (string) $product->getId(),
                'name' => $product->getName(),
                'preferredStore' => $product->getPreferredStore()?->getName(),
                'fallbackStore' => $product->getFallbackStore()?->getName(),
                'shelfLifeDays' => $product->getShelfLifeDays(),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
