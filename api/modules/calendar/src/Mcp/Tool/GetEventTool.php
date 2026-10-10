<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'get_event', description: 'Get one calendar event by its ID: summary, start, end, all-day flag and agenda. Works for past events too. A whole-day event (allDay true) has no startAt/endAt but startDate, endDate EXCLUDED as in Google\'s API (the day after its last day) and lastDay: tell the user « le 1er janvier » or « du 26 au 28 » from startDate to lastDay, never « jusqu\'au » endDate.')]
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

        // The database hands the instants back in its session zone; the event's own zone is the
        // one its owner reads, and the one that keeps an all-day event on its day.
        $zone = new \DateTimeZone($event->getTimeZone());

        return json_encode([
            'event' => [
                'id' => (string) $event->getId(),
                'summary' => $event->getSummary(),
                'allDay' => $event->isAllDay(),
                'startAt' => $event->getStartAt()?->setTimezone($zone)->format('c'),
                'endAt' => $event->getEndAt()?->setTimezone($zone)->format('c'),
                'startDate' => $event->getStartDate()?->format('Y-m-d'),
                'endDate' => $event->getEndDate()?->format('Y-m-d'),
                // The end is excluded, as Google's: the last day is what Maggie announces (MAG-382).
                'lastDay' => $event->getEndDate()?->modify('-1 day')->format('Y-m-d'),
                'timeZone' => $event->getTimeZone(),
                'status' => $event->getStatus()->value,
                'agenda' => $event->getAgenda()->getName(),
                'recurring' => $event->isRecurring(),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
