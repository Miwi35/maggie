<?php

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Core\Entity\User;
use Maggie\Core\Time\DayBound;

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

    /**
     * The events of `[start, end)`: a timed one by its instants, an all-day one
     * by its days, `[startDate, endDate)` (MAG-382) — the days of the range are
     * {@see DayBound}'s, as for the API's filters.
     */
    private function dateRangeQueryBuilder(\DateTimeImmutable $start, \DateTimeImmutable $end): QueryBuilder
    {
        return $this->createQueryBuilder('e')
            ->where('e.status != :cancelled')
            ->andWhere(
                // Non-recurring timed events that overlap with the range
                '(e.rrule IS NULL AND e.startAt IS NOT NULL AND e.startAt < :end AND e.endAt > :start)'
                // Non-recurring all-day events whose days meet the range's
                .' OR (e.rrule IS NULL AND e.startAt IS NULL AND e.startDate <= :lastDay AND e.endDate > :firstDay)'
                // OR recurring event masters (they need expansion)
                .' OR (e.rrule IS NOT NULL)'
            )
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->setParameter('start', $start, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('end', $end, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('firstDay', DayBound::firstDay($start), Types::DATE_IMMUTABLE)
            ->setParameter('lastDay', DayBound::lastDay($end), Types::DATE_IMMUTABLE)
            ->orderBy('e.startAt', 'ASC')
            ->addOrderBy('e.startDate', 'ASC');
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

    /**
     * The events the agenda deduction reads to recognise a habit (MAG-150): the user's own,
     * not cancelled, starting before the horizon, newest first.
     *
     * The horizon is what makes this history rather than a list: a one-off booked years
     * ahead is the first row an unbounded `startAt DESC` would return and says nothing
     * about where the user files things, while a cancelled occurrence is the opposite of a
     * habit. Agendas are fetch-joined because the caller groups by them.
     *
     * @return Event[]
     */
    public function findForAgendaDeduction(User $user, \DateTimeImmutable $before, int $limit): array
    {
        return $this->createQueryBuilder('e')
            ->addSelect('a')
            // An all-day event has a day and no instant: both kinds in one order.
            ->addSelect('COALESCE(e.startAt, e.startDate) AS HIDDEN startedOn')
            ->join('e.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('e.status != :cancelled')
            ->andWhere('e.startAt < :before OR e.startDate <= :lastDay')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('lastDay', DayBound::lastDay($before), Types::DATE_IMMUTABLE)
            ->orderBy('startedOn', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }
}
