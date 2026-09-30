<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Message\DeleteEventCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'delete_event', description: 'Delete a calendar event by its ID. Returns confirmation of deletion.')]
class DeleteEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $this->bus->dispatch(new DeleteEventCommand(eventId: $id));

            return json_encode([
                'success' => true,
                'message' => 'Event has been deleted.',
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
