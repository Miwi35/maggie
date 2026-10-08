<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\RestockProductCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\ProductRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Puts a product's restock quantity back on its owner's list.
 *
 * Returns the list so the Mercure and Elasticsearch middlewares publish and
 * reindex it: the open screens get the line without reloading.
 */
#[AsMessageHandler]
class RestockProductHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(RestockProductCommand $command): GroceryList
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        $quantity = $product->getRestockQuantity();
        if (null === $quantity || $quantity <= 0) {
            throw new \DomainException('This product has no restock quantity.');
        }

        $list = $this->groceryListRepository->findOrCreateForUser($product->getUser());
        $unit = $product->getPackagingUnit();

        $maxPosition = 0;
        $mergeable = null;
        foreach ($list->getItems() as $existing) {
            $maxPosition = max($maxPosition, $existing->getPosition());

            // A ticked line is already in the basket: raising it would hide the new need.
            if (null === $mergeable && !$existing->isChecked() && $this->isLineOf($existing, $command->productId, $unit)) {
                $mergeable = $existing;
            }
        }

        if (null !== $mergeable) {
            $mergeable->setQuantity(($mergeable->getQuantity() ?? 0) + $quantity);
            $mergeable->setUnit($unit);
        } else {
            $item = new GroceryItem();
            $item->setProduct($product);
            $item->setSource(GroceryItemSource::Restock);
            $item->setStore($product->getPreferredStore());
            $item->setQuantity($quantity);
            $item->setUnit($unit);
            $item->setPosition($maxPosition + 1);
            $list->addItem($item);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }

    private function isLineOf(GroceryItem $item, string $productId, ?Unit $unit): bool
    {
        $product = $item->getProduct();
        if (null === $product || (string) $product->getId() !== $productId) {
            return false;
        }

        // Quantities of different units cannot be added; a line with no
        // quantity at all can take the restock one.
        return $item->getUnit() === $unit || null === $item->getQuantity();
    }
}
