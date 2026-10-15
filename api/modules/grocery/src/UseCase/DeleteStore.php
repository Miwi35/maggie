<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Repository\AisleRepository;
use Maggie\Grocery\Repository\ProductRepository;

class DeleteStore
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProductRepository $productRepository,
        private readonly AisleRepository $aisleRepository,
        private readonly DeleteAisle $deleteAisle,
    ) {
    }

    public function execute(Store $store): void
    {
        // The foreign key would null these columns by itself, but then no product is
        // reindexed (the projection reads Doctrine's change set), so their search
        // documents keep naming a store that no longer exists.
        $products = $this->productRepository->findByStore($store);
        foreach ($products as $product) {
            if ($product->getPreferredStore() === $store) {
                $product->setPreferredStore(null);
            }
            if ($product->getFallbackStore() === $store) {
                $product->setFallbackStore(null);
            }
        }

        // Its aisles go with it, announced like the products they leave.
        foreach ($this->aisleRepository->findByStore($store) as $aisle) {
            $this->deleteAisle->detachProducts($aisle);
            $this->em->remove($aisle);
        }

        $this->em->remove($store);
        $this->em->flush();
    }
}
