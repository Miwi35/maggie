<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Message\CreateRecurringGroceryItemCommand;
use Maggie\Grocery\Message\DeleteRecurringGroceryItemCommand;
use Maggie\Grocery\Message\UpdateRecurringGroceryItemCommand;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_recurring_groceries', description: 'List, create, update, or delete recurring grocery items — the staples automatically added to generated lists. Frequency: weekly, biweekly, monthly. Give either a productId or a customLabel. To empty an optional field on update, list its name in clear (productId, customLabel, quantity, unit); an item must keep a productId or a customLabel.')]
class ManageRecurringGroceriesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $recurringItemId = null,
        ?string $frequency = null,
        ?string $productId = null,
        ?string $customLabel = null,
        ?float $quantity = null,
        ?string $unit = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($frequency, $productId, $customLabel, $quantity, $unit),
                'update' => $this->update($recurringItemId, $frequency, $productId, $customLabel, $quantity, $unit, $clear),
                'delete' => $this->delete($recurringItemId),
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

        $items = $this->recurringGroceryItemRepository->findByUser($user);

        return json_encode([
            'recurringItems' => array_map(fn (RecurringGroceryItem $i) => $this->serialize($i), $items),
            'count' => count($items),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $frequency, ?string $productId, ?string $customLabel, ?float $quantity, ?string $unit): string
    {
        if ($frequency === null) {
            return json_encode(['error' => 'frequency is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateRecurringGroceryItemCommand(
            userId: (string) $user->getId(),
            frequency: $frequency,
            productId: $productId,
            customLabel: $customLabel,
            quantity: $quantity,
            unit: $unit,
        ));

        /** @var RecurringGroceryItem $item */
        $item = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'recurringItem' => $this->serialize($item)], JSON_THROW_ON_ERROR);
    }

    /** @param list<string>|null $clear */
    private function update(?string $recurringItemId, ?string $frequency, ?string $productId, ?string $customLabel, ?float $quantity, ?string $unit, ?array $clear): string
    {
        if ($recurringItemId === null) {
            return json_encode(['error' => 'recurringItemId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: $recurringItemId,
            frequency: $frequency,
            productId: $productId,
            customLabel: $customLabel,
            quantity: $quantity,
            unit: $unit,
            clearFields: array_values(array_intersect($clear ?? [], ['productId', 'customLabel', 'quantity', 'unit'])),
        ));

        /** @var RecurringGroceryItem $item */
        $item = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'recurringItem' => $this->serialize($item)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $recurringItemId): string
    {
        if ($recurringItemId === null) {
            return json_encode(['error' => 'recurringItemId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteRecurringGroceryItemCommand(recurringGroceryItemId: $recurringItemId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(RecurringGroceryItem $item): array
    {
        return [
            'id' => (string) $item->getId(),
            'label' => $item->getLabel(),
            'frequency' => $item->getFrequency()->value,
            'quantity' => $item->getQuantity(),
            'unit' => $item->getUnit()?->value,
            'productId' => $item->getProduct() !== null ? (string) $item->getProduct()->getId() : null,
        ];
    }
}
