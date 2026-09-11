<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\CreateProductCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_product', description: 'Create a non-food product (household, hygiene, cleaning). For food items use create_ingredient instead. Categories: household, hygiene, cleaning, other.')]
class CreateProductTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $name,
        string $category,
        ?string $defaultUnit = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $envelope = $this->bus->dispatch(new CreateProductCommand(
                userId: (string) $user->getId(),
                name: $name,
                category: $category,
                defaultUnit: $defaultUnit,
            ));

            /** @var Product $product */
            $product = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'product' => [
                    'id' => (string) $product->getId(),
                    'name' => $product->getName(),
                    'category' => $product->getCategory()->value,
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
