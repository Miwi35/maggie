<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_events_by_date', description: 'Get all events for a specific date (YYYY-MM-DD format). Returns events from all of the user agendas, including expanded recurring events. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate. Every local date comes with its day of the week (startWeekday, endWeekday for a timed event; startWeekday and lastDayWeekday for a whole-day one): say the weekday from those fields or from the date_time tool (weekday action), never work it out yourself. Pass agendas (a list of agenda names as the user says them — case, accents and approximations do not matter — or ids) whenever the user asked to be told only about some agendas (« ne me parle que de Défaut et Garde Ael », a rule to apply to every read from then on): only their events come back, and a name matching no agenda returns an error listing the user\'s agendas and no events at all. Omit it to read every agenda. Each event carries the name of its agenda (agenda) and its times in its own time zone (timeZone): announce those. To find an event by its name on that day, pass query, the distinctive word(s) of its title. When the day is not known, use get_upcoming_events with query instead, never guess a date.')]
class GetEventsByDateTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly McpUserContext $userContext,
        private readonly EventReadFilter $filter,
    ) {
    }

    /**
     * @param list<string>|null $agendas names or ids of the agendas to read
     */
    public function __invoke(string $date, ?array $agendas = null, ?string $query = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $agendaIds = $this->filter->agendaIds($user, $agendas);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $dateObj = \DateTimeImmutable::createFromFormat('!Y-m-d', $date);
        if (false === $dateObj || $dateObj->format('Y-m-d') !== $date) {
            return json_encode(['error' => "date must be a date as YYYY-MM-DD, got \"{$date}\"."], JSON_THROW_ON_ERROR);
        }
        $start = $dateObj->setTime(0, 0);
        $end = $dateObj->setTime(23, 59, 59);

        $events = $this->filter->keep($this->eventRepository->findByDateRange($user, $start, $end), $agendaIds, $query);
        $expanded = $this->recurrenceService->expandAll($events, $start, $end);

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
            'rrule' => $event->getRrule(),
        ], $expanded);

        return json_encode(['date' => $date, 'events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
