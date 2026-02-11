<?php

namespace Maggie\Agenda\Mcp\Tool;

use Maggie\Agenda\Repository\EventRepository;
use Doctrine\ORM\EntityManagerInterface;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'delete_event', description: 'Delete a calendar event by its ID. Returns confirmation of deletion.')]
class DeleteEventTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(string $id): string
    {
        $event = $this->eventRepository->find($id);
        if ($event === null) {
            return json_encode(['error' => "Event not found: {$id}"], JSON_THROW_ON_ERROR);
        }

        $summary = $event->getSummary();
        $this->em->remove($event);
        $this->em->flush();

        return json_encode([
            'success' => true,
            'message' => "Event '{$summary}' has been deleted.",
        ], JSON_THROW_ON_ERROR);
    }
}
