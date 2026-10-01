<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\CreateProductCommand;
use Maggie\Grocery\Message\DeleteProductCommand;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_products', description: 'List, create, update, or delete non-food products (household, hygiene, cleaning, other). Create food items with manage_ingredients instead — they are products too, so they show up in the list. To search by name use search_products. To empty an optional field on update, list its name in clear (defaultUnit).')]
class ManageProductsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProductRepository $productRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $productId = null,
        ?string $name = null,
        ?string $category = null,
        ?string $defaultUnit = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $category, $defaultUnit),
                'update' => $this->update($productId, $name, $category, $defaultUnit, $clear),
                'delete' => $this->delete($productId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $products = $this->productRepository->findByUser($user);

        return json_encode([
            'products' => array_map(fn (Product $p) => $this->serialize($p), $products),
            'count' => count($products),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?string $category, ?string $defaultUnit): string
    {
        if (null === $name || null === $category) {
            return json_encode(['error' => 'name and category are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateProductCommand(
            userId: (string) $user->getId(),
            name: $name,
            category: $category,
            defaultUnit: $defaultUnit,
        ));

        /** @var Product $product */
        $product = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'product' => $this->serialize($product)], JSON_THROW_ON_ERROR);
    }

    /** @param list<string>|null $clear */
    private function update(?string $productId, ?string $name, ?string $category, ?string $defaultUnit, ?array $clear): string
    {
        if (null === $productId) {
            return json_encode(['error' => 'productId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateProductCommand(
            productId: $productId,
            name: $name,
            category: $category,
            defaultUnit: $defaultUnit,
            clearFields: array_values(array_intersect($clear ?? [], ['defaultUnit'])),
        ));

        /** @var Product $product */
        $product = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'product' => $this->serialize($product)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $productId): string
    {
        if (null === $productId) {
            return json_encode(['error' => 'productId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteProductCommand(productId: $productId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Product $product): array
    {
        return [
            'id' => (string) $product->getId(),
            'name' => $product->getName(),
            'category' => $product->getCategory()->value,
            'defaultUnit' => $product->getDefaultUnit()?->value,
        ];
    }
}
