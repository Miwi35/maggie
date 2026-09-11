<?php

namespace Maggie\Calendar\Repository;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Event>
 */
class EventRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Event::class);
    }

    /**
     * Find non-cancelled events within a date range (non-recurring + recurring masters).
     *
     * @return Event[]
     */
    public function findByDateRange(\DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        return $this->createQueryBuilder('e')
            ->where('e.status != :cancelled')
            ->andWhere(
                // Non-recurring events that overlap with the range
                '(e.rrule IS NULL AND e.startAt < :end AND e.endAt > :start)'
                // OR recurring event masters (they need expansion)
                .' OR (e.rrule IS NOT NULL)'
            )
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->setParameter('start', $start)
            ->setParameter('end', $end)
            ->orderBy('e.startAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Find upcoming non-recurring, non-cancelled events.
     *
     * @return Event[]
     */
    public function findUpcoming(int $days = 7): array
    {
        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        return $this->findByDateRange($now, $end);
    }

    /**
     * Find events on a specific date.
     *
     * @return Event[]
     */
    public function findByDate(\DateTimeImmutable $date): array
    {
        $start = $date->setTime(0, 0);
        $end = $date->setTime(23, 59, 59);

        return $this->findByDateRange($start, $end);
    }

    /**
     * Find exception instances for a recurring event.
     *
     * @return Event[]
     */
    public function findExceptionsForRecurringEvent(Event $recurringEvent): array
    {
        return $this->createQueryBuilder('e')
            ->where('e.recurringEvent = :parent')
            ->setParameter('parent', $recurringEvent->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    public function findByGoogleEventId(string $googleEventId, Agenda $agenda): ?Event
    {
        return $this->createQueryBuilder('e')
            ->where('e.googleEventId = :googleEventId')
            ->andWhere('e.agenda = :agenda')
            ->setParameter('googleEventId', $googleEventId)
            ->setParameter('agenda', $agenda->getId(), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * @return Event[]
     */
    public function findGoogleSyncedByAgenda(Agenda $agenda): array
    {
        return $this->createQueryBuilder('e')
            ->where('e.agenda = :agenda')
            ->andWhere('e.googleEventId IS NOT NULL')
            ->setParameter('agenda', $agenda->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }
}
