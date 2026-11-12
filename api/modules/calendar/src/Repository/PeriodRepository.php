<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Period;
use Maggie\Core\Entity\User;
use Maggie\Core\Time\DayBound;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Period> */
class PeriodRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Period::class);
    }

    /** @return list<Period> by start, the timed and the all-day ones mixed */
    public function findByUser(User|string $user): array
    {
        $periods = $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->setParameter('user', $user instanceof User ? $user->getId() : Ulid::fromString($user), 'ulid')
            ->getQuery()
            ->getResult();
        usort($periods, static fn (Period $a, Period $b): int => $a->getStartInstant() <=> $b->getStartInstant());

        /* @var list<Period> */
        return $periods;
    }

    /** The user's period, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $periodId): ?Period
    {
        if (!Ulid::isValid($periodId)) {
            return null;
        }

        return $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('p.id = :id')
            ->andWhere('a.user = :user')
            ->setParameter('id', Ulid::fromString($periodId), 'ulid')
            ->setParameter('user', $user instanceof User ? $user->getId() : Ulid::fromString($user), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The user's periods that may cover `[from, to)`: the single ones meeting it,
     * a timed one by its instants and an all-day one by its days, and every series
     * begun before `to` — unfold those with {@see \Maggie\Calendar\Service\PeriodRecurrence}.
     *
     * @return list<Period>
     */
    public function findCovering(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<Period> */
        return $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere(
                '(p.rrule IS NULL AND p.startAt IS NOT NULL AND p.startAt < :to AND p.endAt > :from)'
                .' OR (p.rrule IS NULL AND p.startAt IS NULL AND p.startDate <= :lastDay AND p.endDate > :firstDay)'
                .' OR (p.rrule IS NOT NULL AND ((p.startAt IS NOT NULL AND p.startAt < :to) OR (p.startAt IS NULL AND p.startDate <= :lastDay)))'
            )
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('firstDay', DayBound::firstDay($from), Types::DATE_IMMUTABLE)
            ->setParameter('lastDay', DayBound::lastDay($to), Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getResult();
    }
}
