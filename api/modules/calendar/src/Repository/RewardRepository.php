<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Reward;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Reward> */
class RewardRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Reward::class);
    }

    public function findOneBySubject(User $user, string $subjectType, string $subjectId): ?Reward
    {
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->andWhere('r.subjectType = :type')
            ->andWhere('r.subjectId = :id')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('type', $subjectType)
            ->setParameter('id', $subjectId)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** The user's last reward: its message is not drawn again. */
    public function findLatest(User $user): ?Reward
    {
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('r.earnedAt', 'DESC')
            ->addOrderBy('r.id', 'DESC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** Whether a surprise — of that kind, when given — was earned in [$from, $to[: one a day at most. */
    public function hasSurpriseBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to, ?string $surprise = null): bool
    {
        $query = $this->createQueryBuilder('r')
            ->select('r.id')
            ->where('r.user = :user')
            ->andWhere('r.surprise IS NOT NULL')
            ->andWhere('r.earnedAt >= :from')
            ->andWhere('r.earnedAt < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->setMaxResults(1);
        if (null !== $surprise) {
            $query->andWhere('r.surprise = :surprise')->setParameter('surprise', $surprise);
        }

        return null !== $query->getQuery()->getOneOrNullResult();
    }

    /**
     * When each of the user's stars before $before was earned, to count them by month.
     *
     * @return list<\DateTimeImmutable>
     */
    public function findStarDatesBefore(User $user, \DateTimeImmutable $before): array
    {
        $rows = $this->createQueryBuilder('r')
            ->select('r.earnedAt')
            ->where('r.user = :user')
            ->andWhere('r.star = true')
            ->andWhere('r.earnedAt < :before')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->getQuery()
            ->getArrayResult();

        return array_values(array_map(static fn (array $row): \DateTimeImmutable => $row['earnedAt'], $rows));
    }

    /** The user's stars earned in [$from, $to[. */
    public function countStarsBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): int
    {
        return (int) $this->createQueryBuilder('r')
            ->select('COUNT(r.id)')
            ->where('r.user = :user')
            ->andWhere('r.star = true')
            ->andWhere('r.earnedAt >= :from')
            ->andWhere('r.earnedAt < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->getQuery()
            ->getSingleScalarResult();
    }

    /** Every star the user ever earned: the total only goes up. */
    public function countStars(User $user): int
    {
        return (int) $this->createQueryBuilder('r')
            ->select('COUNT(r.id)')
            ->where('r.user = :user')
            ->andWhere('r.star = true')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getSingleScalarResult();
    }

    /** When the latest star earned in [$from, $to[ was earned, null when there is none. */
    public function findLatestStarDateBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): ?\DateTimeImmutable
    {
        $row = $this->createQueryBuilder('r')
            ->select('r.earnedAt')
            ->where('r.user = :user')
            ->andWhere('r.star = true')
            ->andWhere('r.earnedAt >= :from')
            ->andWhere('r.earnedAt < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('r.earnedAt', 'DESC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();

        return null === $row ? null : $row['earnedAt'];
    }
}
