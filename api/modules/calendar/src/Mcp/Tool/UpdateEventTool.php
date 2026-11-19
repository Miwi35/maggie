<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Calendar\Service\EventReminders;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'update_event', description: 'Update an event by id (master id for a series); only given fields change. Schedule, given whole, never a duration: start_date (YYYY-MM-DD) + start_time (HH:MM) + end_date + end_time, or all_day true + start_date + end_date; end_date is EXCLUDED. An incomplete schedule or an end not after the start changes nothing and returns an error with currentSchedule. clear: fields to empty (description, location, rrule, reminders). agenda_id: name or id; unknown or ambiguous: error listing the agendas. status: tentative or confirmed. reminders: minutes before start; replaces the set. Returns the saved event in its own time zone: allDay; startAt, endAt (timed); startDate, endDate, lastDay (whole-day); status; weekday, startWeekday, endWeekday, lastDayWeekday for each local date.')]
class UpdateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly AgendaResolver $agendaResolver,
        private readonly EventRepository $eventRepository,
    ) {
    }

    /**
     * @param list<string>|null $clear
     * @param list<int>|null    $reminders minutes before the start, e.g. [60] for "une heure avant"
     */
    public function __invoke(
        string $id,
        ?string $title = null,
        ?string $start_date = null,
        ?string $start_time = null,
        ?string $end_date = null,
        ?string $end_time = null,
        ?bool $all_day = null,
        ?string $description = null,
        ?string $location = null,
        ?array $clear = null,
        ?string $agenda_id = null,
        ?array $reminders = null,
        ?string $status = null,
    ): string {
        $current = null;

        try {
            $user = $this->userContext->requireUser();

            // Another user's event answers exactly like a missing one — and its schedule is
            // never read back in an error.
            $found = Ulid::isValid($id) ? $this->eventRepository->find($id) : null;
            if (null === $found || (string) $found->getAgenda()->getUser()->getId() !== (string) $user->getId()) {
                throw new \DomainException("Event not found: {$id}");
            }
            $current = $found;

            $eventStatus = EventStatus::settable($status);

            $schedule = EventSchedule::fromParts(
                $start_date,
                $start_time,
                $end_date,
                $end_time,
                $all_day,
                EventSchedule::zoneOf($current),
            );

            if (null !== $agenda_id) {
                $agenda_id = (string) $this->agendaResolver
                    ->resolve($user, $agenda_id)
                    ->getId();
            }

            $clearFields = array_values(array_intersect(
                $clear ?? [],
                ['description', 'location', 'rrule', 'reminders'],
            ));

            $newReminders = null;
            if (null !== $reminders) {
                $newReminders = EventReminders::fromMinutes(array_map('intval', $reminders));
                // `reminders: []` asks for no reminder left. Said that way it is the
                // same order as `clear`, from the field the user was talking about.
                if (null === $newReminders && !\in_array('reminders', $clearFields, true)) {
                    $clearFields[] = 'reminders';
                }
            }

            $envelope = $this->bus->dispatch(new UpdateEventCommand(
                eventId: $id,
                summary: $title,
                startAt: $schedule?->startAt,
                endAt: $schedule?->endAt,
                startDate: $schedule?->startDate,
                endDate: $schedule?->endDate,
                description: $description,
                location: $location,
                allDay: $schedule?->allDay,
                status: $eventStatus?->value,
                agendaId: $agenda_id,
                reminders: $newReminders,
                clearFields: $clearFields,
            ));

            /** @var Event $event */
            $event = $envelope->last(HandledStamp::class)->getResult();

            $described = EventSchedule::describe($event);

            return json_encode([
                'success' => true,
                'event' => [
                    'id' => (string) $event->getId(),
                    'summary' => $event->getSummary(),
                    ...$described,
                    'weekday' => $described['startWeekday'],
                    'agenda' => $event->getAgenda()->getName(),
                    'status' => $event->getStatus()->value,
                    'reminders' => EventReminders::toMinutes($event->getReminders()),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\DomainException $e) {
            return $this->error($e->getMessage(), $current);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return $this->error($cause->getMessage(), $current);
        }
    }

    /** The schedule the event has now rides along, so that Maggie completes it instead of guessing. */
    private function error(string $message, ?Event $current): string
    {
        $error = ['error' => $message];
        if (null !== $current) {
            $error['currentSchedule'] = EventSchedule::describe($current);
        }

        return json_encode($error, JSON_THROW_ON_ERROR);
    }
}
