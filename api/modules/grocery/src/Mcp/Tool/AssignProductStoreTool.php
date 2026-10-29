<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Identifier\CanonicalId;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'assign_product_store', description: 'Set the preferred/fallback store, the shelf life and the aisle of a product. shelfLifeDays indicates how many days the product stays fresh (null = non-perishable). aisle puts the product in an aisle of a store, by aisle id or by name — a name the store does not have yet creates the aisle, last on the way through the store (see manage_aisles); an empty string leaves the product without an aisle there. The aisle is in aisleStoreId, else in the preferred store (the one given here, or the product\'s). A product has one aisle per store: « Crèmerie » at one store and « Frais » at another are two calls.')]
class AssignProductStoreTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProductRepository $productRepository,
        private readonly McpUserContext $userContext,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    public function __invoke(
        string $productId,
        ?string $preferredStoreId = null,
        ?string $fallbackStoreId = null,
        ?int $shelfLifeDays = null,
        ?string $aisle = null,
        ?string $aisleStoreId = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $product = $this->productRepository->find($productId);
            if (null === $product) {
                $line = $this->entityManager->find(GroceryItem::class, $productId);
                if (null !== $line && (string) $line->getGroceryList()->getUser()->getId() === (string) $user->getId()) {
                    $product = $line->getProduct();
                    if (null !== $product) {
                        $productId = (string) $product->getId();
                    }
                }
            }
            if (null === $product || (string) $product->getUser()->getId() !== (string) $user->getId()) {
                return self::error("Product not found: {$productId} — pass the productId (add_grocery_item returns it, search_products finds it), not an unknown id");
            }

            $aisles = null;
            if (null !== $aisle) {
                $preferred = $product->getPreferredStore();
                $storeId = $aisleStoreId ?? $preferredStoreId ?? (null !== $preferred ? (string) $preferred->getId() : null);
                if (null === $storeId) {
                    return self::error('Which store is the aisle in? Give aisleStoreId, or a preferred store first.');
                }
                $aisles = self::withAisle($product, CanonicalId::of($storeId), '' === trim($aisle) ? null : $aisle);
            }

            $envelope = $this->bus->dispatch(new UpdateProductCommand(
                productId: $productId,
                preferredStoreId: $preferredStoreId,
                fallbackStoreId: $fallbackStoreId,
                shelfLifeDays: $shelfLifeDays,
                aisles: $aisles,
            ));
        } catch (MissingMcpUserException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            return self::error(($e->getPrevious() ?? $e)->getMessage());
        }

        /** @var Product $product */
        $product = $envelope->last(HandledStamp::class)?->getResult();

        $aisleNames = [];
        foreach ($product->getAisleAssignments() as $assignment) {
            $aisleNames[] = [
                'storeId' => (string) $assignment->getStore()->getId(),
                'store' => $assignment->getStore()->getName(),
                'aisle' => $assignment->getAisle()->toSummary(),
            ];
        }

        return json_encode([
            'success' => true,
            'product' => [
                'id' => (string) $product->getId(),
                'name' => $product->getName(),
                'preferredStore' => $product->getPreferredStore()?->getName(),
                'fallbackStore' => $product->getFallbackStore()?->getName(),
                'shelfLifeDays' => $product->getShelfLifeDays(),
                'aisles' => $aisleNames,
            ],
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * The product's aisles with this store's replaced: the command takes them all.
     *
     * @return list<array{store: string, aisle: ?string}>
     */
    private static function withAisle(Product $product, string $storeId, ?string $aisle): array
    {
        $aisles = array_values(array_filter(
            $product->getAisles(),
            fn (array $entry) => $entry['store'] !== $storeId,
        ));
        $aisles[] = ['store' => $storeId, 'aisle' => $aisle];

        return $aisles;
    }

    private static function error(string $message): string
    {
        return json_encode(['error' => $message], JSON_THROW_ON_ERROR);
    }
}
