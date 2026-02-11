<?php

namespace Maggie\Agenda\Mcp\Tool;

use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\Service\RecurrenceService;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_events_by_date', description: 'Get all events for a specific date (YYYY-MM-DD format). Returns events from all calendars, including expanded recurring events.')]
class GetEventsByDateTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
    ) {
    }

    public function __invoke(string $date): string
    {
        $dateObj = new \DateTimeImmutable($date);
        $start = $dateObj->setTime(0, 0);
        $end = $dateObj->setTime(23, 59, 59);

        $events = $this->eventRepository->findByDateRange($start, $end);
        $expanded = $this->recurrenceService->expandAll($events, $start, $end);

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
        ], $expanded);

        return json_encode(['date' => $date, 'events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
