<?php

declare(strict_types=1);

namespace Maggie\Calendar\Home;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeDay;
use Maggie\Core\Time\DayBound;
use Symfony\Component\Clock\ClockInterface;

/**
 * The Agenda card of the Accueil (MAG-562): the events of the day, whole-day ones first
 * then the timed ones by start. An event that began before the day and runs into it counts.
 *
 * Which of them are past, in progress or next is the client's to say, from `now`: the line
 * « maintenant » moves with the clock, the answer does not. `now` is null for tomorrow, which
 * has no line.
 */
final class AgendaHomeCard implements HomeCardProviderInterface
{
    public function __construct(
        private readonly EventRepository $events,
        private readonly RecurrenceService $recurrence,
        private readonly ClockInterface $clock,
    ) {
    }

    public function module(): string
    {
        return 'agenda';
    }

    public function state(User $user, HomeDay $day): array
    {
        $zone = new \DateTimeZone(DayBound::OWNER_TIME_ZONE);
        $date = $day->date($this->clock)->format('Y-m-d');
        $from = new \DateTimeImmutable($date, $zone);
        $until = $from->modify('+1 day');

        $occurrences = $this->recurrence->expandAll($this->events->findByDateRange($user, $from, $until), $from, $until);
        usort($occurrences, static fn (Event $a, Event $b): int => [$a->isAllDay() ? 0 : 1, $a->getStartInstant(), $a->getSummary()]
            <=> [$b->isAllDay() ? 0 : 1, $b->getStartInstant(), $b->getSummary()]);

        return [
            'date' => $date,
            'now' => HomeDay::Today === $day ? $this->clock->now()->setTimezone($zone)->format('c') : null,
            'events' => array_map(self::describe(...), $occurrences),
        ];
    }

    /** @return array<string, mixed> */
    private static function describe(Event $event): array
    {
        $agenda = $event->getAgenda();

        return [
            'id' => (string) $event->getId(),
            'summary' => $event->getSummary(),
            'description' => $event->getDescription(),
            'location' => $event->getLocation(),
            'locationShort' => self::shortLocation($event->getLocation()),
            // Schedule in the event's own zone; a whole-day one carries its days, the end excluded, and `lastDay`.
            ...EventSchedule::describe($event),
            'status' => $event->getStatus()->value,
            'reminders' => $event->getReminders(),
            'activity' => $event->getActivity(),
            'agendaId' => (string) $agenda->getId(),
            'agendaName' => $agenda->getName(),
            'agendaColor' => $agenda->getColor(),
        ];
    }

    /** « Cabinet Dr Martin, 15 rue de la Paix » → « Cabinet Dr Martin »: the place, not the address. */
    private static function shortLocation(?string $location): ?string
    {
        if (null === $location || '' === trim($location)) {
            return null;
        }

        $place = trim(explode(',', $location, 2)[0]);

        return '' === $place ? trim($location) : $place;
    }
}
