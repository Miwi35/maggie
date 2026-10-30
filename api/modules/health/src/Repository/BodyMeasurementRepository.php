<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Symfony\Component\Uid\Ulid;

/**
 * The instants are bound as DATETIMETZ: bound without their offset, they would
 * be read in the database's zone and a day would start at the wrong hour.
 *
 * @extends ServiceEntityRepository<BodyMeasurement>
 */
class BodyMeasurementRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, BodyMeasurement::class);
    }

    /** @return list<BodyMeasurement> the most recent first, excluded ones included */
    public function findByUser(
        User $user,
        ?\DateTimeImmutable $after = null,
        ?\DateTimeImmutable $before = null,
        int $limit = 30,
    ): array {
        $qb = $this->createQueryBuilder('m')
            ->andWhere('m.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('m.measuredAt', 'DESC')
            ->setMaxResults($limit);

        if (null !== $after) {
            $qb->andWhere('m.measuredAt >= :after')->setParameter('after', $after, Types::DATETIMETZ_IMMUTABLE);
        }
        if (null !== $before) {
            $qb->andWhere('m.measuredAt <= :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE);
        }

        /* @var list<BodyMeasurement> */
        return $qb->getQuery()->getResult();
    }

    /**
     * The day's weight (MAG-706): the first weighing, not excluded, of the
     * local day of $localDay — from its midnight, in its own zone (the user's,
     * given by the caller: Health knows no user zone), to the next. Null when
     * that day has none: never the day before's.
     */
    public function firstOfDay(User $user, \DateTimeImmutable $localDay): ?BodyMeasurement
    {
        $start = $localDay->setTime(0, 0);
        $end = $start->modify('+1 day');

        /* @var BodyMeasurement|null */
        return $this->createQueryBuilder('m')
            ->andWhere('m.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('m.excluded = false')
            ->andWhere('m.weight IS NOT NULL')
            ->andWhere('m.measuredAt >= :start')->setParameter('start', $start, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('m.measuredAt < :end')->setParameter('end', $end, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('m.measuredAt', 'ASC')
            ->addOrderBy('m.id', 'ASC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The profile's weight (MAG-707): the latest weighing not set aside,
     * whatever its day; null when the user has none.
     */
    public function latestWeighing(User $user): ?BodyMeasurement
    {
        /* @var BodyMeasurement|null */
        return $this->createQueryBuilder('m')
            ->andWhere('m.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('m.excluded = false')
            ->andWhere('m.weight IS NOT NULL')
            ->orderBy('m.measuredAt', 'DESC')
            ->addOrderBy('m.id', 'DESC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** The user's measurement, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $measurementId): ?BodyMeasurement
    {
        if (!Ulid::isValid($measurementId)) {
            return null;
        }

        return $this->findOneBy(['id' => $measurementId, 'user' => $user]);
    }

    public function findOneByExternalId(User|string $user, string $externalId): ?BodyMeasurement
    {
        return $this->findOneBy(['user' => $user, 'externalId' => $externalId]);
    }
}
