<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\EndErrandCommand;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'end_errand', description: 'End shopping errand. Removes all checked (bought) items and returns remaining unchecked items. The agent should then ask the user about EACH remaining item: keep on the list for later, or remove. Use remove_grocery_item to remove items the user no longer wants.')]
class EndErrandTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? throw new \DomainException('No user found.');

            $envelope = $this->bus->dispatch(new EndErrandCommand(
                userId: (string) $user->getId(),
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

            $remaining = [];
            foreach ($list->getItems() as $item) {
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
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
