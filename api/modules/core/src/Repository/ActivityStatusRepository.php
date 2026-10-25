<?php

declare(strict_types=1);

namespace Maggie\Core\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Entity\User;
use Maggie\Core\Enum\Activity;

/**
 * @extends ServiceEntityRepository<ActivityStatus>
 */
class ActivityStatusRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, ActivityStatus::class);
    }

    public function findOneByUserAndActivity(User $user, Activity $activity): ?ActivityStatus
    {
        return $this->findOneBy(['user' => $user, 'activity' => $activity]);
    }

    /**
     * The user's activities in progress, the latest touched first.
     *
     * @return list<ActivityStatus>
     */
    public function findByUser(User $user, ?Activity $activity = null): array
    {
        $criteria = ['user' => $user];
        if (null !== $activity) {
            $criteria['activity'] = $activity;
        }

        return $this->findBy($criteria, ['updatedAt' => 'DESC', 'id' => 'ASC']);
    }
}
