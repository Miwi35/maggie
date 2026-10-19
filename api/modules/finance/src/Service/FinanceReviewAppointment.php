<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Core\Entity\User;
use Maggie\Finance\Message\EnsureFinanceReviewAppointmentCommand;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The monthly review's appointment (MAG-456): on the 25th, 20:00–21:00, in the
 * user's « Finance » module agenda, the event the clients launch the review from.
 *
 * Laid down once, with the agenda, the first time the user opens their
 * finances: the user may move or delete it afterwards, and it never comes back
 * while the agenda exists.
 */
class FinanceReviewAppointment
{
    public const AGENDA_NAME = 'Finance';
    public const AGENDA_COLOR = '#7FD1AE';
    public const SUMMARY = 'Revue du mois';
    public const RRULE = 'FREQ=MONTHLY;BYMONTHDAY=25';
    public const DAY = 25;
    public const START_TIME = '20:00';
    public const DURATION = '+1 hour';

    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * Lays the appointment down through the bus the first time, so the agenda
     * and the event reach the open screens and the search index; a read once
     * the agenda exists, afterwards.
     *
     * Call it once the response is computed: two first reads racing (the
     * dashboard and the month balance load together) both try to create the
     * agenda, and the loser's flush fails on its unique key — that one is
     * swallowed, the winner laid the appointment, but it leaves the entity
     * manager closed for the rest of the request.
     */
    public function ensure(User $user): void
    {
        if (null !== $this->agendaRepository->findOneByModule($user, Agenda::MODULE_FINANCE)) {
            return;
        }

        try {
            $this->bus->dispatch(new EnsureFinanceReviewAppointmentCommand((string) $user->getId()));
        } catch (HandlerFailedException $e) {
            if (!$e->getPrevious() instanceof UniqueConstraintViolationException) {
                throw $e;
            }
        }
    }

    /**
     * Creates the Finance agenda and its recurring appointment, if the agenda
     * does not exist yet. Run by {@see EnsureFinanceReviewAppointmentCommand}'s handler.
     *
     * @return Event|null the appointment created, null when the agenda was already there
     */
    public function lay(User $user): ?Event
    {
        [$agenda, $created] = $this->moduleAgendas->forUser($user, Agenda::MODULE_FINANCE, self::AGENDA_NAME, self::AGENDA_COLOR, adoptLegacy: false);
        if (!$created) {
            return null;
        }

        $zone = new \DateTimeZone($agenda->getTimeZone());
        $start = new \DateTimeImmutable(
            $this->clock->now()->setTimezone($zone)->format('Y-m-').self::DAY.' '.self::START_TIME,
            $zone,
        );

        $event = new Event();
        $event->setSummary(self::SUMMARY);
        $event->setTimeZone($agenda->getTimeZone());
        $event->scheduleTimed($start, $start->modify(self::DURATION));
        $event->setRrule(self::RRULE);
        $event->setActivity(Event::ACTIVITY_FINANCE_REVIEW);
        $agenda->addEvent($event);

        $this->em->persist($event);
        $this->em->flush();

        return $event;
    }
}
