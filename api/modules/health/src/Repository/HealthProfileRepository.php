<?php

declare(strict_types=1);

namespace Maggie\Health\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthProfile;

/** @extends ServiceEntityRepository<HealthProfile> */
class HealthProfileRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, HealthProfile::class);
    }

    public function findOneByUser(User|string $user): ?HealthProfile
    {
        return $this->findOneBy(['user' => $user]);
    }
}
