<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
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

#[McpTool(name: 'create_event', description: 'Create a new calendar event. Always give the whole schedule — a start and an end, never a duration, nothing is assumed: start_date (YYYY-MM-DD) + start_time (HH:MM) + end_date + end_time, or for a whole-day event all_day true + start_date + end_date (the last day included; the same day for a single day). An event running past midnight has an end_date the day after. An incomplete schedule, or an end that is not after the start, creates nothing and returns an error. The result gives the schedule that was saved, in the event\'s own time zone (event.startAt, event.endAt, event.allDay; startDate and endDate for a whole-day event): announce exactly that to the user, not what you meant to do. Pass agenda_id whenever the user said or implied where the event belongs — its name as they said it ("Concerts", "au boulot": case, accents and approximations do not matter) or its id, with no manage_agendas call first. Omit it when they said nothing about the agenda: the tool then works it out from the event itself, from the agendas\' names and descriptions, and from the agenda the user filed similar events in before. Two agendas fitting equally well, or a name matching none, returns an error naming the plausible agendas and creates nothing — ask the user which one they mean and retry, never pick one yourself. Tell the user which agenda the event went to: it is in event.agenda. Use reminders for "préviens-moi une heure avant": a list of delays in minutes before the start, e.g. [60] or [10, 1440]; omit it when the user asked for nothing. Use rrule for a repeating event, RFC 5545 without the RRULE: prefix — "FREQ=WEEKLY;BYDAY=MO", "FREQ=DAILY;COUNT=10", "FREQ=MONTHLY;INTERVAL=2". The start given is the first occurrence, and reminders then fire for every occurrence.')]
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
    ): string {
        $user = $this->userContext->getUser();
        $zone = new \DateTimeZone(Event::FALLBACK_TIME_ZONE);

        try {
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
                agendaId: null !== $choice?->agenda ? (string) $choice->agenda->getId() : null,
                description: $description,
                location: $location,
                timeZone: $zone->getName(),
                allDay: $schedule->allDay,
                rrule: '' !== $rrule ? $rrule : null,
                reminders: EventReminders::fromMinutes(array_map('intval', $reminders ?? [])),
                userId: null !== $user ? (string) $user->getId() : null,
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
