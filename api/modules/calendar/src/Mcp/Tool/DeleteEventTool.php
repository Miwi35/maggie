<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Message\DeleteEventCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'delete_event', description: 'Delete a calendar event by its ID. Returns confirmation of deletion. When the user names the event rather than gives its id (« supprime le piano du jeudi »), find it first with get_upcoming_events and query (the distinctive word(s) of its title, no days: it searches the next year), or search with types events — never from a 7-day window, and never answer that it is not found before that search came back empty. A recurring series is designated by its master event: the id the search returns is the series\' own, and deleting it removes every occurrence.')]
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
