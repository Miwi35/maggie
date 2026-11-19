<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_upcoming_events', description: 'Gets events of the next N days (default 7) from all agendas, recurring events expanded. A whole-day event (allDay true) has startDate, endDate (EXCLUDED: the day after its last day) and lastDay, no startAt/endAt. Local dates come with their weekday (startWeekday, endWeekday; whole-day: startWeekday, lastDayWeekday). Each event has agenda (name) and timeZone. agendas: names (case and accents ignored) or ids; an unknown name returns an error listing the agendas. Omit to read all. query: word(s) of a title or place; without days, searches a year and a recurring series comes back once, as master (id, rrule, next occurrence in startAt).')]
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
            // The activity the event launches (finance-review: the monthly review, MAG-456).
            'activity' => $event->getActivity(),
            'agenda' => $event->getAgenda()->getName(),
            'recurring' => $event->isRecurring(),
            'rrule' => $event->getRrule(),
        ], $expanded);

        return json_encode(['events' => $result, 'count' => count($result)] + $this->filter->mealsHint($searching, $result), JSON_THROW_ON_ERROR);
    }
}
