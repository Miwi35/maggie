<?php

namespace Maggie\Calendar\Repository;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Core\Entity\User;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\ORM\QueryBuilder;
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
     * Find a user's non-cancelled events within a date range (non-recurring + recurring masters).
     *
     * @return Event[]
     */
    public function findByDateRange(User $user, \DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        return $this->dateRangeQueryBuilder($start, $end)
            ->join('e.agenda', 'a')
            ->andWhere('a.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    /**
     * Same as findByDateRange() for every user: only for system jobs that act on behalf of each owner.
     *
     * @return Event[]
     */
    public function findByDateRangeForAllUsers(\DateTimeImmutable $start, \DateTimeImmutable $end): array
    {
        return $this->dateRangeQueryBuilder($start, $end)
            ->getQuery()
            ->getResult();
    }

    /**
     * Find upcoming non-recurring, non-cancelled events of a user.
     *
     * @return Event[]
     */
    public function findUpcoming(User $user, int $days = 7): array
    {
        $now = new \DateTimeImmutable('now');
        $end = $now->modify("+{$days} days");

        return $this->findByDateRange($user, $now, $end);
    }

    /**
     * Find a user's events on a specific date.
     *
     * @return Event[]
     */
    public function findByDate(User $user, \DateTimeImmutable $date): array
    {
        $start = $date->setTime(0, 0);
        $end = $date->setTime(23, 59, 59);

        return $this->findByDateRange($user, $start, $end);
    }

    private function dateRangeQueryBuilder(\DateTimeImmutable $start, \DateTimeImmutable $end): QueryBuilder
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
            ->orderBy('e.startAt', 'ASC');
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
