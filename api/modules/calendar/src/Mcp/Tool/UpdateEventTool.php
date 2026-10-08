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

#[McpTool(name: 'update_event', description: 'Update an existing calendar event. Only provided fields will be updated. To change when it happens, always give the whole schedule — a start and an end, never a duration: start_date (YYYY-MM-DD) + start_time (HH:MM) + end_date + end_time, or for a whole-day event all_day true + start_date + end_date (the last day included). For "du X au Y", holidays, a trip or any stretch of several days, make ONE whole-day event with all_day true, start_date and end_date: never simulate a range with rrule (that makes one event per day) or with a long timed event. Nothing is deduced from the current schedule: moving an event to another day means giving its start and its end again. An incomplete schedule, or an end that is not after the start, changes nothing and returns an error with currentSchedule, the schedule the event has now — complete it from there instead of guessing. The result gives the schedule that was saved, in the event\'s own time zone (event.startAt, event.endAt, event.allDay; startDate and endDate for a whole-day event): announce exactly that to the user, not what you meant to do. Title, location, description, agenda and reminders change on their own, without touching the schedule. To empty an optional field, list its name in clear (description, location, rrule, reminders). Use agenda_id to move the event to another agenda: pass its name as the user said it (e.g. "Concerts", case and accents do not matter) or its id — no need to call manage_agendas first. An unknown or ambiguous name returns an error listing the user\'s agendas. Use status to mark the event \'tentative\' ("provisoire", "à confirmer", "peut-être") or back to \'confirmed\' ("confirme", "c\'est validé"): it changes on its own, without touching the schedule, and event.status gives the status that was saved — say so when the event is tentative. Use reminders to replace the whole set of reminders: a list of delays in minutes before the start, e.g. [60] or [10, 1440]; it replaces what the event had, so pass every reminder the user wants to keep, and clear "reminders" to leave none.')]
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

            return json_encode([
                'success' => true,
                'event' => [
                    'id' => (string) $event->getId(),
                    'summary' => $event->getSummary(),
                    ...EventSchedule::describe($event),
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
