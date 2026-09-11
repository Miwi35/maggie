<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\MoveToFallbackCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'move_to_fallback', description: 'When a store is closed, move all unchecked items from that store to their fallback stores.')]
class MoveToFallbackTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $storeId): string
    {
        try {
            $user = $this->userContext->requireUser();

            $envelope = $this->bus->dispatch(new MoveToFallbackCommand(
                userId: (string) $user->getId(),
                storeId: $storeId,
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'itemCount' => $list->getItems()->count(),
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
