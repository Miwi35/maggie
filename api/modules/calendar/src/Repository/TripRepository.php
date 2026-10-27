<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Trip> */
class TripRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Trip::class);
    }

    public function findOneByEvent(Event $event): ?Trip
    {
        return $this->findOneBy(['event' => $event]);
    }

    /** @return list<Trip> the user's trips leaving within [$from, $to), by departure */
    public function findLeavingBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        /* @var list<Trip> */
        return $this->createQueryBuilder('t')
            ->join('t.event', 'e')
            ->join('e.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('t.departAt >= :from AND t.departAt < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('t.departAt', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
