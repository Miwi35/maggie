<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutExercise;
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
}
