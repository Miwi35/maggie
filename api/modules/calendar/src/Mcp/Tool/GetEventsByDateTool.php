<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_events_by_date', description: 'Gets all events of a date (YYYY-MM-DD) from all agendas, recurring events expanded. A whole-day event (allDay true) has startDate, endDate (EXCLUDED: the day after its last day) and lastDay, no startAt/endAt. Local dates come with their weekday (startWeekday, endWeekday; whole-day: startWeekday, lastDayWeekday). Each event has agenda (name) and timeZone. agendas: names (case and accents ignored) or ids; a name matching no agenda returns an error listing the agendas. Omit to read all. query: word(s) of a title or place. endDate (YYYY-MM-DD, at most 31 days after date): every day from date to endDate in one answer.')]
class GetEventsByDateTool
{
    /** The longest stretch of days one call reads. */
    private const MAX_RANGE_DAYS = 31;

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
    public function __invoke(string $date, ?array $agendas = null, ?string $query = null, ?string $endDate = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $agendaIds = $this->filter->agendaIds($user, $agendas);
            $this->filter->assertSearchable($query);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $dateObj = \DateTimeImmutable::createFromFormat('!Y-m-d', $date);
        if (false === $dateObj || $dateObj->format('Y-m-d') !== $date) {
            return json_encode(['error' => "date must be a date as YYYY-MM-DD, got \"{$date}\"."], JSON_THROW_ON_ERROR);
        }
        $lastDay = $dateObj;
        if (null !== $endDate) {
            $lastDay = \DateTimeImmutable::createFromFormat('!Y-m-d', $endDate);
            if (false === $lastDay || $lastDay->format('Y-m-d') !== $endDate) {
                return json_encode(['error' => "endDate must be a date as YYYY-MM-DD, got \"{$endDate}\"."], JSON_THROW_ON_ERROR);
            }
            if ($lastDay < $dateObj) {
                return json_encode(['error' => "endDate {$endDate} is before date {$date}."], JSON_THROW_ON_ERROR);
            }
            if ($dateObj->diff($lastDay)->days >= self::MAX_RANGE_DAYS) {
                return json_encode(['error' => sprintf('endDate must be at most %d days after date, ask for the stretch in several calls.', self::MAX_RANGE_DAYS - 1)], JSON_THROW_ON_ERROR);
            }
        }
        $start = $dateObj->setTime(0, 0);
        $end = $lastDay->setTime(23, 59, 59);

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
            // The activity the event launches (finance-review: the monthly review, MAG-456).
            'activity' => $event->getActivity(),
            'agenda' => $event->getAgenda()->getName(),
            'recurring' => $event->isRecurring(),
            'rrule' => $event->getRrule(),
        ], $expanded);

        return json_encode(['date' => $date] + (null === $endDate ? [] : ['endDate' => $endDate]) + ['events' => $result, 'count' => count($result)] + $this->filter->mealsHint($this->filter->hasQuery($query), $result), JSON_THROW_ON_ERROR);
    }
}
