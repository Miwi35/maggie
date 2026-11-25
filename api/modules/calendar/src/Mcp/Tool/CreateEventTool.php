<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Service\AgendaCandidate;
use Maggie\Calendar\Service\AgendaChoice;
use Maggie\Calendar\Service\AgendaChoiceKind;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Calendar\Service\AgendaSuggester;
use Maggie\Calendar\Service\EventReminders;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_event', description: 'An event is an appointment or a dated activity on one of the owner\'s agendas, possibly inside a period; what fills the whole time and makes him unavailable is an event even over several days. A context during which other things happen and get planned (a trip, holidays, custody, a stay away) is a period (manage_periods), never a long event; a planned meal is a meal (manage_meals); a thing he has to do is a task (create_task). Before a timed event, call check_conflicts on its slot; a conflict: create nothing, tell him and ask. A period over the slot is no conflict. Give a start and an end, never a duration: start_date (YYYY-MM-DD) + start_time (HH:MM) + end_date + end_time, or all_day true + start_date + end_date; end_date is EXCLUDED (1 January alone: 2037-01-01 to 2037-01-02). An incomplete schedule or an end not after the start creates nothing and returns an error. agenda_id: agenda name or id; omitted, inferred. Two equally plausible agendas, or a name matching none: error naming them, nothing created. reminders: minutes before start, e.g. [10, 1440]. rrule: RFC 5545 without the RRULE: prefix, e.g. FREQ=WEEKLY;BYDAY=MO; status: tentative or confirmed. Returns the saved event in its own time zone: allDay; startAt, endAt (timed); startDate, endDate, lastDay (whole-day); agenda; status; weekday, startWeekday, endWeekday, lastDayWeekday for each local date.')]
class CreateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly AgendaResolver $agendaResolver,
        private readonly AgendaSuggester $agendaSuggester,
    ) {
    }

    /** @param list<int>|null $reminders minutes before the start, e.g. [60] for "une heure avant" */
    public function __invoke(
        string $title,
        ?string $start_date = null,
        ?string $start_time = null,
        ?string $end_date = null,
        ?string $end_time = null,
        ?bool $all_day = null,
        ?string $description = null,
        ?string $location = null,
        ?string $agenda_id = null,
        ?array $reminders = null,
        ?string $rrule = null,
        ?string $status = null,
    ): string {
        $user = $this->userContext->getUser();
        $zone = new \DateTimeZone(Event::FALLBACK_TIME_ZONE);

        try {
            $eventStatus = EventStatus::settable($status);
            $schedule = EventSchedule::required($start_date, $start_time, $end_date, $end_time, $all_day, $zone);

            if (null === $user) {
                // Nothing to deduce from and nobody to ask: naming an agenda is refused
                // outright, and omitting one falls to the handler's own guard.
                if (null !== $agenda_id) {
                    throw new MissingMcpUserException();
                }
                $choice = null;
            } else {
                $choice = $this->chooseAgenda($user, $title, $description, $location, $agenda_id);
            }

            $envelope = $this->bus->dispatch(new CreateEventCommand(
                summary: $title,
                startAt: $schedule->startAt,
                endAt: $schedule->endAt,
                startDate: $schedule->startDate,
                endDate: $schedule->endDate,
                agendaId: null !== $choice?->agenda ? (string) $choice->agenda->getId() : null,
                description: $description,
                location: $location,
                timeZone: $zone->getName(),
                allDay: $schedule->allDay,
                rrule: '' !== $rrule ? $rrule : null,
                status: $eventStatus?->value,
                reminders: EventReminders::fromMinutes(array_map('intval', $reminders ?? [])),
                userId: null !== $user ? (string) $user->getId() : null,
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
                    'rrule' => $event->getRrule(),
                    'reminders' => EventReminders::toMinutes($event->getReminders()),
                ],
                // How the agenda was settled, so the sentence the user reads says what
                // actually happened instead of what the model assumes happened (MAG-150).
                'agendaChoice' => $choice?->kind->value,
                'agendaReason' => $choice?->reason,
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    /**
     * The agenda this event goes in: the one the user named, the one the deduction settles
     * on, or a question (MAG-150).
     *
     * @throws \DomainException when the answer is a question — and then nothing is created
     */
    private function chooseAgenda(User $user, string $title, ?string $description, ?string $location, ?string $reference): AgendaChoice
    {
        if (null !== $reference && '' === trim($reference)) {
            $reference = null;
        }

        if (null !== $reference) {
            // Exactly as before: one call, the name as it was spoken (MAG-230). Several
            // agendas carrying that name still throws — that is an ambiguity in the
            // agendas themselves, and no deduction may pick one of two identical names.
            $exact = $this->agendaResolver->findExact($user, $reference);
            if (null !== $exact) {
                return AgendaChoice::named($exact);
            }
        }

        $choice = $this->agendaSuggester->suggest($user, $title, $description, $location, $reference);

        return match ($choice->kind) {
            AgendaChoiceKind::Ambiguous => throw $this->askWhichOne($choice),
            // A reference that matched nothing and pointed nowhere is not an absent
            // agenda: the user did say something, and booking somewhere else instead of
            // asking is the behaviour this ticket exists to remove.
            AgendaChoiceKind::Ask => throw null !== $reference ? $this->agendaResolver->unknownReference($user, $reference) : new \DomainException(sprintf('No default agenda is set: ask which agenda to use, or pass agenda_id (ids come from manage_agendas with action list). %s', $this->agendaResolver->describeFor($user))),
            default => $choice,
        };
    }

    private function askWhichOne(AgendaChoice $choice): \DomainException
    {
        return new \DomainException(sprintf(
            'Several agendas fit this event as well as each other: %s. Nothing was created: ask the user which one they mean, then retry with that agenda_id.',
            implode('; ', array_map(static fn (AgendaCandidate $c) => $c->describe(), $choice->candidates)),
        ));
    }
}
