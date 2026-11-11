<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<WorkoutMakeup> */
class WorkoutMakeupRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, WorkoutMakeup::class);
    }

    /** The user's proposal, or null — also for an id that is neither a ULID nor its IRI. */
    public function findOneOfUser(User|string $user, string $makeupId): ?WorkoutMakeup
    {
        $parts = explode('/', rtrim($makeupId, '/'));
        $id = end($parts);
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }

    public function findOneByMissed(Workout $missed): ?WorkoutMakeup
    {
        return $this->findOneBy(['missed' => $missed]);
    }
}
