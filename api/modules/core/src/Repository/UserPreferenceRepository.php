<?php

namespace Maggie\Core\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;

/**
 * @extends ServiceEntityRepository<UserPreference>
 */
class UserPreferenceRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, UserPreference::class);
    }

    public function findOneByUser(User $user): ?UserPreference
    {
        return $this->findOneBy(['user' => $user]);
    }
}
