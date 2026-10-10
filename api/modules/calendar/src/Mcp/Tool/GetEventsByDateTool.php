<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_events_by_date', description: 'Get all events for a specific date (YYYY-MM-DD format). Returns events from all of the user agendas, including expanded recurring events. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate.')]
class GetEventsByDateTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $dateObj = new \DateTimeImmutable($date);
        $start = $dateObj->setTime(0, 0);
        $end = $dateObj->setTime(23, 59, 59);

        $events = $this->eventRepository->findByDateRange($user, $start, $end);
        $expanded = $this->recurrenceService->expandAll($events, $start, $end);

        $result = array_map(fn ($event) => [
            'id' => (string) $event->getId(),
            'summary' => $event->getSummary(),
            'description' => $event->getDescription(),
            'location' => $event->getLocation(),
            'allDay' => $event->isAllDay(),
            'startAt' => $event->getStartAt()?->format('c'),
            'endAt' => $event->getEndAt()?->format('c'),
            'startDate' => $event->getStartDate()?->format('Y-m-d'),
            'endDate' => $event->getEndDate()?->format('Y-m-d'),
            // The end is excluded, as Google's: the last day is what Maggie announces (MAG-382).
            'lastDay' => $event->getEndDate()?->modify('-1 day')->format('Y-m-d'),
            'status' => $event->getStatus()->value,
            'agenda' => $event->getAgenda()->getName(),
        ], $expanded);

        return json_encode(['date' => $date, 'events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
