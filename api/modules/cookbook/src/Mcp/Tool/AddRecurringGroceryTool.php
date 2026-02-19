<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Maggie\Cookbook\Message\CreateRecurringGroceryItemCommand;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'add_recurring_grocery', description: 'Add a recurring grocery item that is automatically included in generated lists. Frequency: weekly, biweekly, monthly.')]
class AddRecurringGroceryTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(
        string $frequency,
        ?string $productId = null,
        ?string $customLabel = null,
        ?float $quantity = null,
        ?string $unit = null,
    ): string {
        try {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? throw new \DomainException('No user found.');

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

            return json_encode([
                'success' => true,
                'recurringItem' => [
                    'id' => (string) $item->getId(),
                    'label' => $item->getLabel(),
                    'frequency' => $item->getFrequency()->value,
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
