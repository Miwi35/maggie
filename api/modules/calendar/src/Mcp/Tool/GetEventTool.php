<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'get_event', description: 'Get one calendar event by its ID: summary, start, end, all-day flag and agenda. Works for past events too. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate. Every local date comes with its day of the week (startWeekday, endWeekday for a timed event; startWeekday and lastDayWeekday for a whole-day one): say the weekday from those fields or from the date_time tool (weekday action), never work it out yourself.')]
class GetEventTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $event = Ulid::isValid($id) ? $this->eventRepository->find($id) : null;

        // Another user's event answers exactly like a missing one.
        if (null === $event || (string) $event->getAgenda()->getUser()->getId() !== (string) $user->getId()) {
            return json_encode(['error' => "Event not found: {$id}"], JSON_THROW_ON_ERROR);
        }

        return json_encode([
            'event' => [
                'id' => (string) $event->getId(),
                'summary' => $event->getSummary(),
                'allDay' => $event->isAllDay(),
                // In the event's own zone, not the database session's, with the day of the week of
                // each local date (MAG-431).
                ...EventSchedule::describe($event),
                'status' => $event->getStatus()->value,
                // The activity the event launches (finance-review: the monthly review, MAG-456).
                'activity' => $event->getActivity(),
                'agenda' => $event->getAgenda()->getName(),
                'recurring' => $event->isRecurring(),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
