<?php

namespace App\Mcp\Tool;

use App\Repository\EventRepository;
use App\Service\RecurrenceService;
use Mcp\Capability\Attribute\McpTool;

class GetUpcomingEventsTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
    ) {
    }

    #[McpTool(name: 'get_upcoming_events', description: 'Get upcoming events for the next N days (default 7). Returns events from all calendars, including expanded recurring events.')]
    public function __invoke(int $days = 7): string
    {
        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        $events = $this->eventRepository->findByDateRange($now, $end);
        $expanded = $this->recurrenceService->expandAll($events, $now, $end);

        $result = array_map(fn ($event) => [
            'id' => (string) $event->getId(),
            'summary' => $event->getSummary(),
            'description' => $event->getDescription(),
            'location' => $event->getLocation(),
            'allDay' => $event->isAllDay(),
            'startAt' => $event->getStartAt()->format('c'),
            'endAt' => $event->getEndAt()->format('c'),
            'status' => $event->getStatus()->value,
            'calendar' => $event->getCalendar()->getName(),
            'recurring' => $event->isRecurring(),
        ], $expanded);

        return json_encode(['events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
