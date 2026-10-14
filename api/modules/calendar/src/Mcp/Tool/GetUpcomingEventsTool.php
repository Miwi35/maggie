<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_upcoming_events', description: 'Get upcoming events for the next N days (default 7). Returns events from all of the user agendas, including expanded recurring events. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate. Every local date comes with its day of the week (startWeekday, endWeekday for a timed event; startWeekday and lastDayWeekday for a whole-day one): say the weekday from those fields or from the date_time tool (weekday action), never work it out yourself. Pass agendas (a list of agenda names as the user says them — case, accents and approximations do not matter — or ids) whenever the user asked to be told only about some agendas (« ne me parle que de Défaut et Garde Ael », a rule to apply to every read from then on): only their events come back, and a name matching no agenda returns an error listing the user\'s agendas and no events at all. Omit it to read every agenda. Each event carries the name of its agenda (agenda) and its times in its own time zone (timeZone): announce those. To find an event by its name — before deleting or moving it, or before saying it does not exist — pass query, the distinctive word(s) of its title (case, accents and typos do not matter), and no days: the search then covers the next year, not a week; a recurring series comes back once, as its master event (id, rrule, first coming occurrence in startAt). Never conclude that an event does not exist from a short window; pass that id to update_event or delete_event.')]
class GetUpcomingEventsTool
{
    /** How far a search by title looks when the caller gave no window of its own. */
    private const TITLE_SEARCH_DAYS = 365;

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
    public function __invoke(?int $days = null, ?array $agendas = null, ?string $query = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $agendaIds = $this->filter->agendaIds($user, $agendas);
            $this->filter->assertSearchable($query);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $searching = $this->filter->hasQuery($query);
        $days ??= $searching ? self::TITLE_SEARCH_DAYS : 7;

        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        $events = $this->filter->keep($this->eventRepository->findByDateRange($user, $now, $end), $agendaIds, $searching ? $query : null);
        $expanded = $this->recurrenceService->expandAll($events, $now, $end);

        if ($searching) {
            // A series is one answer, designated by its master: a weekly event would
            // otherwise fill the page with the same id.
            $expanded = array_values(array_reduce($expanded, static function (array $kept, $event) {
                $kept[(string) $event->getId()] ??= $event;

                return $kept;
            }, []));
        }

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

        return json_encode(['events' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
