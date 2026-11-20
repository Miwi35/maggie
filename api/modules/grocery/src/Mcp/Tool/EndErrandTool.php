<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Message\EndErrandCommand;
use Maggie\Grocery\Message\EndErrandResult;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'end_errand', description: 'Archives checked items as past errands, one per store (errands: id, storeName, itemCount), removes them from the list, puts their products In Stock (restockedProducts), returns the unchecked items. storeId ends that store only; paidAmountCents (whole cents, needs storeId) records what was paid.')]
class EndErrandTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        ?string $storeId = null,
        ?int $paidAmountCents = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $envelope = $this->bus->dispatch(new EndErrandCommand(
                userId: (string) $user->getId(),
                storeId: $storeId,
                paidAmountCents: $paidAmountCents,
            ));

            /** @var EndErrandResult $result */
            $result = $envelope->last(HandledStamp::class)->getResult();

            $remaining = [];
            foreach ($result->list->getItems() as $item) {
                $remaining[] = [
                    'id' => (string) $item->getId(),
                    'label' => $item->getLabel(),
                    'quantity' => $item->getQuantity(),
                    'unit' => $item->getUnit()?->value,
                    'store' => $item->getStore()?->getName(),
                ];
            }

            return json_encode([
                'success' => true,
                'checkedRemoved' => true,
                'remainingItems' => $remaining,
                'remainingCount' => count($remaining),
                'restockedProducts' => array_map(static fn ($product) => [
                    'id' => (string) $product->getId(),
                    'name' => $product->getName(),
                    'stockState' => $product->getStockState()->value,
                ], $result->restockedProducts),
                'errands' => array_map(static fn ($errand) => [
                    'id' => (string) $errand->getId(),
                    'storeName' => $errand->getStoreName(),
                    'itemCount' => $errand->getItemCount(),
                    'paidAmountCents' => $errand->getPaidAmountCents(),
                ], $result->errands),
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
