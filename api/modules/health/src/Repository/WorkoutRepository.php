<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
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
}
