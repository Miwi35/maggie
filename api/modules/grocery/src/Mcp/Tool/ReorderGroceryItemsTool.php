<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Grocery\Message\ReorderGroceryItemsCommand;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'reorder_grocery_items', description: 'Reorder grocery items by setting their position. Pass an array of {id, position} pairs.')]
class ReorderGroceryItemsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
    ) {
    }

    /**
     * @param array<array{id: string, position: int}> $items
     */
    public function __invoke(array $items): string
    {
        $users = $this->userRepository->findAll();
        $user = $users[0] ?? null;

        if ($user === null) {
            return json_encode(['error' => 'No user found.'], JSON_THROW_ON_ERROR);
        }

        try {
            $this->bus->dispatch(new ReorderGroceryItemsCommand(
                userId: (string) $user->getId(),
                items: $items,
            ));

            return json_encode(['success' => true], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
