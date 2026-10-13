<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_upcoming_events', description: 'Get upcoming events for the next N days (default 7). Returns events from all of the user agendas, including expanded recurring events. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate. Every local date comes with its day of the week (startWeekday, endWeekday for a timed event; startWeekday and lastDayWeekday for a whole-day one): say the weekday from those fields or from the date_time tool (weekday action), never work it out yourself.')]
class GetUpcomingEventsTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(int $days = 7): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        $events = $this->eventRepository->findByDateRange($user, $now, $end);
        $expanded = $this->recurrenceService->expandAll($events, $now, $end);

        $result = array_map(fn ($event) => [
            'id' => (string) $event->getId(),
            'summary' => $event->getSummary(),
            'description' => $event->getDescription(),
            'location' => $event->getLocation(),
            'allDay' => $event->isAllDay(),
            // The schedule in the event's own zone, with the day of the week of each local date (MAG-431).
            ...EventSchedule::describe($event),
            'status' => $event->getStatus()->value,
            'agenda' => $event->getAgenda()->getName(),
            'recurring' => $event->isRecurring(),
        ], $expanded);

        return json_encode(['events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
