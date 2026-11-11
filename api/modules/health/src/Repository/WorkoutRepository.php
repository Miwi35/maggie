<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\AbstractQuery;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutExercise;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Workout> */
class WorkoutRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Workout::class);
    }

    /** @return list<Workout> the most recent first */
    public function findByUser(
        User $user,
        ?WorkoutType $type = null,
        ?WorkoutSource $source = null,
        ?\DateTimeImmutable $after = null,
        ?\DateTimeImmutable $before = null,
        int $limit = 50,
        ?string $exercise = null,
    ): array {
        $qb = $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('w.startedAt', 'DESC')
            ->setMaxResults($limit);

        if (null !== $type) {
            $qb->andWhere('w.type = :type')->setParameter('type', $type);
        }
        if (null !== $source) {
            $qb->andWhere('w.source = :source')->setParameter('source', $source);
        }
        if (null !== $exercise) {
            $qb->andWhere('EXISTS (SELECT 1 FROM '.WorkoutExercise::class.' we WHERE we.workout = w AND we.exercise = :exercise)')->setParameter('exercise', $exercise);
        }
        if (null !== $after) {
            $qb->andWhere('w.startedAt >= :after')->setParameter('after', $after);
        }
        if (null !== $before) {
            $qb->andWhere('w.startedAt <= :before')->setParameter('before', $before);
        }

        /* @var list<Workout> */
        return $qb->getQuery()->getResult();
    }

    /** The user's workout, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $workoutId): ?Workout
    {
        if (!Ulid::isValid($workoutId)) {
            return null;
        }

        return $this->findOneBy(['id' => $workoutId, 'user' => $user]);
    }

    public function findOneByExternalId(User|string $user, string $externalId): ?Workout
    {
        return $this->findOneBy(['user' => $user, 'externalId' => $externalId]);
    }

    /**
     * The strength sessions done (not planned), the most recent first, started
     * before $before excluded when given.
     *
     * @return list<Workout>
     */
    public function findDoneStrength(User $user, ?\DateTimeImmutable $before = null): array
    {
        $qb = $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.type = :type')->setParameter('type', WorkoutType::Strength)
            ->andWhere('w.source != :planned')->setParameter('planned', WorkoutSource::Planned)
            ->orderBy('w.startedAt', 'DESC');
        if (null !== $before) {
            $qb->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE);
        }

        /* @var list<Workout> */
        return $qb->getQuery()->getResult();
    }

    /**
     * The strength sessions, planned or done, started from $from included to
     * $before excluded, the earliest first; `$except` is left out.
     *
     * @return list<Workout>
     */
    public function findStrengthInRange(User $user, \DateTimeImmutable $from, \DateTimeImmutable $before, ?string $except = null): array
    {
        $qb = $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.type = :type')->setParameter('type', WorkoutType::Strength)
            ->andWhere('w.startedAt >= :from')->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('w.startedAt', 'ASC');
        if (null !== $except) {
            $qb->andWhere('w.id != :except')->setParameter('except', $except, 'ulid');
        }

        /* @var list<Workout> */
        return $qb->getQuery()->getResult();
    }

    /**
     * The sessions done (not planned) that started from $from included to
     * $before excluded, each bound read as an instant.
     *
     * @return list<Workout>
     */
    public function findDoneInRange(User $user, \DateTimeImmutable $from, \DateTimeImmutable $before): array
    {
        /* @var list<Workout> */
        return $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.source != :planned')->setParameter('planned', WorkoutSource::Planned)
            ->andWhere('w.startedAt >= :from')->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('w.startedAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** The session done that keeps this planned one, if any (MAG-771). */
    public function findKeeperOf(Workout $planned): ?Workout
    {
        return $this->findOneBy(['keeps' => $planned]);
    }

    /**
     * The planned sessions a session done may still keep (MAG-771), started
     * from $from included to $before excluded, the earliest first: not kept
     * yet, and not replaced by a planned make-up (the make-up is kept instead).
     *
     * @return list<Workout>
     */
    public function findKeepablePlanned(User $user, \DateTimeImmutable $from, \DateTimeImmutable $before): array
    {
        /* @var list<Workout> */
        return $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.source = :planned')->setParameter('planned', WorkoutSource::Planned)
            ->andWhere('w.startedAt >= :from')->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('NOT EXISTS (SELECT 1 FROM '.Workout::class.' k WHERE k.keeps = w)')
            ->andWhere('NOT EXISTS (SELECT 1 FROM '.Workout::class.' m WHERE m.makeupOf = w)')
            ->orderBy('w.startedAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Every user's planned sessions that may have been missed (MAG-771):
     * started from $from included to $before excluded, not make-ups
     * themselves, not kept, with no make-up proposed yet.
     *
     * @return list<Workout>
     */
    public function findMissCandidates(\DateTimeImmutable $from, \DateTimeImmutable $before): array
    {
        /* @var list<Workout> */
        return $this->createQueryBuilder('w')
            ->andWhere('w.source = :planned')->setParameter('planned', WorkoutSource::Planned)
            ->andWhere('w.makeupOf IS NULL')
            ->andWhere('w.startedAt >= :from')->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->andWhere('NOT EXISTS (SELECT 1 FROM '.Workout::class.' k WHERE k.keeps = w)')
            ->andWhere('NOT EXISTS (SELECT 1 FROM '.WorkoutMakeup::class.' p WHERE p.missed = w)')
            ->orderBy('w.startedAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The user's planned sessions that are not make-ups, started before
     * $before excluded, the most recent first: what a streak counts.
     *
     * @return list<Workout>
     */
    public function findPlannedOriginalsBefore(User $user, \DateTimeImmutable $before): array
    {
        /* @var list<Workout> */
        return $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.source = :planned')->setParameter('planned', WorkoutSource::Planned)
            ->andWhere('w.makeupOf IS NULL')
            ->andWhere('w.startedAt < :before')->setParameter('before', $before, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('w.startedAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The user's planned make-ups (MAG-771), whatever they replace.
     *
     * @return list<Workout>
     */
    public function findMakeups(User $user): array
    {
        /* @var list<Workout> */
        return $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.makeupOf IS NOT NULL')
            ->getQuery()
            ->getResult();
    }

    /** @return array<string, true> the ids (base 32) of the user's planned sessions kept by a session done */
    public function findKeptIds(User $user): array
    {
        /** @var list<Workout> $keepers */
        $keepers = $this->createQueryBuilder('w')
            ->andWhere('w.user = :user')->setParameter('user', $user->getId(), 'ulid')
            ->andWhere('w.keeps IS NOT NULL')
            ->getQuery()
            ->getResult();

        $ids = [];
        foreach ($keepers as $keeper) {
            $ids[(string) $keeper->getKeeps()?->getId()] = true;
        }

        return $ids;
    }

    /**
     * The id of the session filed under this event, read without loading it:
     * the event's deletion asks during the flush (MAG-777).
     */
    public function findIdByEvent(Ulid $eventId): ?string
    {
        $id = $this->createQueryBuilder('w')
            ->select('w.id')
            ->andWhere('IDENTITY(w.event) = :event')->setParameter('event', $eventId, 'ulid')
            ->getQuery()
            ->getOneOrNullResult(AbstractQuery::HYDRATE_SCALAR_COLUMN);

        // Scalar hydration may hand the column over as the database spells it (RFC 4122): back to a ULID.
        return null === $id ? null : (string) Ulid::fromString((string) $id);
    }

    public function findOneByEvent(User|string $user, Ulid $eventId): ?Workout
    {
        return $this->findOneBy(['user' => $user, 'event' => $eventId]);
    }

    /**
     * The planned sessions not filed in the agenda yet, oldest first: what
     * `app:health:file-planned-workouts` has left to do (MAG-777).
     *
     * @return list<array{string, string}> [workout id, user id] of each
     */
    public function findPlannedWithoutEvent(): array
    {
        /** @var list<array{id: mixed, userId: mixed}> $rows */
        $rows = $this->createQueryBuilder('w')
            ->select('w.id', 'IDENTITY(w.user) AS userId')
            ->andWhere('w.source = :planned')->setParameter('planned', WorkoutSource::Planned)
            ->andWhere('w.event IS NULL')
            ->orderBy('w.startedAt', 'ASC')
            ->getQuery()
            ->getScalarResult();

        return array_map(static fn (array $row): array => [
            (string) Ulid::fromString((string) $row['id']),
            (string) Ulid::fromString((string) $row['userId']),
        ], $rows);
    }
}
