<?php

declare(strict_types=1);

namespace Maggie\Core\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;

/**
 * @extends ServiceEntityRepository<UserLocation>
 */
class UserLocationRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, UserLocation::class);
    }

    public function findOneByUser(User $user): ?UserLocation
    {
        return $this->findOneBy(['user' => $user]);
    }
}
