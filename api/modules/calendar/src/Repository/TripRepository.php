<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Trip> */
class TripRepository extends ServiceEntityRepository
{
    /**
     * The occurrence a series' trip serves was not cancelled, and did not become an exception with
     * a trip of its own (MAG-862); a modified one without is the series trip's still, which the
     * recheck moves onto it. An all-day exception is known by the midnight UTC of its day
     * ({@see \Maggie\Calendar\Service\RecurrenceService}), within the Paris day. Needs `:cancelled`.
     */
    private const string OCCURRENCE_STILL_OF_THE_SERIES = 'NOT EXISTS (SELECT x.id FROM '.Event::class.' x WHERE x.recurringEvent = e'
        .' AND (x.originalStartAt = t.occurrenceStartAt'
        ." OR (e.startAt IS NULL AND x.originalStartAt > t.occurrenceStartAt AND x.originalStartAt < DATE_ADD(t.occurrenceStartAt, 1, 'day')))"
        .' AND (x.status = :cancelled OR EXISTS (SELECT own.id FROM '.Trip::class.' own WHERE own.event = x AND own.leg = t.leg)))';

    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Trip::class);
    }

    /** A single event's trip of that leg: it has one occurrence. */
    public function findOneByEvent(Event $event, TripLeg $leg = TripLeg::Outbound): ?Trip
    {
        return $this->findOneBy(['event' => $event, 'leg' => $leg]);
    }

    /** The event's trip of that leg for an occurrence starting within [$from, $to) — a day of a series has one. */
    public function findOneByEventBetween(Event $event, \DateTimeImmutable $from, \DateTimeImmutable $to, TripLeg $leg = TripLeg::Outbound): ?Trip
    {
        /* @var Trip|null */
        return $this->createQueryBuilder('t')
            ->where('t.event = :event AND t.leg = :leg')
            ->andWhere('t.occurrenceStartAt >= :from AND t.occurrenceStartAt < :to')
            ->setParameter('event', $event->getId(), 'ulid')
            ->setParameter('leg', $leg)
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * Every trip whose time to leave has come and whose occurrence has not started (MAG-862) —
     * the ones a departure alert is owed for (MAG-655). A cancelled event, or occurrence, is not. A stay has no start:
     * its arrival stands in; a return is owed its alert for {@see Trip::RETURN_ALERT_MINUTES} minutes.
     *
     * @return list<Trip>
     */
    public function findDepartureDue(\DateTimeImmutable $now): array
    {
        /* @var list<Trip> */
        return $this->createQueryBuilder('t')
            ->join('t.event', 'e')
            ->where('t.departAt <= :now')
            ->andWhere('((t.leg = :return AND t.departAt > :returnSince) OR (t.leg = :outbound AND COALESCE(t.arriveAt, t.occurrenceStartAt) > :now))')
            ->andWhere('e.status != :cancelled')
            ->andWhere(self::OCCURRENCE_STILL_OF_THE_SERIES)
            ->setParameter('now', $now, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('returnSince', $now->modify(sprintf('-%d minutes', Trip::RETURN_ALERT_MINUTES)), Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('return', TripLeg::Return)
            ->setParameter('outbound', TripLeg::Outbound)
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->orderBy('t.departAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Trips leaving within `$window` whose occurrence has not started: the candidates of a check
     * before departure (MAG-730). A cancelled event, or occurrence, is not.
     *
     * @return list<Trip>
     */
    public function findLeavingWithin(\DateTimeImmutable $now, \DateInterval $window): array
    {
        /* @var list<Trip> */
        return $this->createQueryBuilder('t')
            ->join('t.event', 'e')
            ->where('t.departAt > :now AND t.departAt <= :limit')
            ->andWhere('(t.leg = :return OR COALESCE(t.arriveAt, t.occurrenceStartAt) > :now)')
            ->andWhere('e.status != :cancelled')
            ->andWhere(self::OCCURRENCE_STILL_OF_THE_SERIES)
            ->setParameter('now', $now, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('return', TripLeg::Return)
            ->setParameter('limit', $now->add($window), Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->orderBy('t.departAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return list<Trip> the user's trips leaving within [$from, $to), by departure, but those of an occurrence cancelled or planned on its own */
    public function findLeavingBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<Trip> */
        return $this->createQueryBuilder('t')
            ->join('t.event', 'e')
            ->join('e.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('t.departAt >= :from AND t.departAt < :to')
            ->andWhere(self::OCCURRENCE_STILL_OF_THE_SERIES)
            ->setParameter('cancelled', EventStatus::Cancelled)
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('t.departAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** Whether the user has a trip leaving within [$from, $to). */
    public function hasLeavingBetween(string $userId, \DateTimeImmutable $from, \DateTimeImmutable $to): bool
    {
        return null !== $this->createQueryBuilder('t')
            ->select('t.id')
            ->join('t.event', 'e')
            ->join('e.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('t.departAt >= :from AND t.departAt < :to')
            ->setParameter('user', Ulid::fromString($userId), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
