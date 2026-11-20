<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_events', description: 'Gets events from all agendas, recurring events expanded: one day, a range, or a search by name. from: first day (YYYY-MM-DD), default now. to: last day, included; default from + 6 days (from + 365 days with query). At most 31 days without query, 366 with. query: word(s) of a title or place; a recurring series comes back once, as master (id, rrule, first occurrence of the window in startAt). agendas: names (case and accents ignored) or ids; a name matching no agenda returns an error listing the agendas. Omit to read all. A whole-day event (allDay true) has startDate, endDate (EXCLUDED: the day after its last day) and lastDay, no startAt/endAt. Local dates come with their weekday (startWeekday, endWeekday; whole-day: startWeekday, lastDayWeekday). Each event has agenda (name) and timeZone. Returns {from, to, events, count}.')]
class GetEventsTool
{
    /** The longest stretch of days one call reads without a search. */
    private const MAX_RANGE_DAYS = 31;

    /** The longest stretch of days one call reads when searching by name. */
    private const SEARCH_RANGE_DAYS = 366;

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
    public function __invoke(?string $from = null, ?string $to = null, ?string $query = null, ?array $agendas = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $agendaIds = $this->filter->agendaIds($user, $agendas);
            $this->filter->assertSearchable($query);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $searching = $this->filter->hasQuery($query);
        $maxDays = $searching ? self::SEARCH_RANGE_DAYS : self::MAX_RANGE_DAYS;

        $now = new \DateTimeImmutable('now');
        $firstDay = $now->setTime(0, 0);
        if (null !== $from) {
            $firstDay = \DateTimeImmutable::createFromFormat('!Y-m-d', $from);
            if (false === $firstDay || $firstDay->format('Y-m-d') !== $from) {
                return json_encode(['error' => "from must be a date as YYYY-MM-DD, got \"{$from}\"."], JSON_THROW_ON_ERROR);
            }
        }

        if (null === $to) {
            $lastDay = $firstDay->modify($searching ? '+365 days' : '+6 days');
        } else {
            $lastDay = \DateTimeImmutable::createFromFormat('!Y-m-d', $to);
            if (false === $lastDay || $lastDay->format('Y-m-d') !== $to) {
                return json_encode(['error' => "to must be a date as YYYY-MM-DD, got \"{$to}\"."], JSON_THROW_ON_ERROR);
            }
            if ($lastDay < $firstDay) {
                return json_encode(['error' => sprintf('to %s is before from %s.', $to, $firstDay->format('Y-m-d'))], JSON_THROW_ON_ERROR);
            }
            if ($firstDay->diff($lastDay)->days >= $maxDays) {
                return json_encode(['error' => sprintf('The range is at most %d days (to at most %d days after from)%s, ask for the stretch in several calls.', $maxDays, $maxDays - 1, $searching ? '' : ' without query')], JSON_THROW_ON_ERROR);
            }
        }

        // Without from, the read starts now: events already over today stay out.
        $start = null === $from ? $now : $firstDay;
        $end = $lastDay->setTime(23, 59, 59);

        $events = $this->filter->keep($this->eventRepository->findByDateRange($user, $start, $end), $agendaIds, $searching ? $query : null);
        $expanded = $this->recurrenceService->expandAll($events, $start, $end);

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

        return json_encode(['from' => $firstDay->format('Y-m-d'), 'to' => $lastDay->format('Y-m-d'), 'events' => $result, 'count' => count($result)] + $this->filter->mealsHint($searching, $result), JSON_THROW_ON_ERROR);
    }
}
