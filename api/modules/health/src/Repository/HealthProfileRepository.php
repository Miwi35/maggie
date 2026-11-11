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

    /**
     * The profiles of the users who fast (MAG-770). A user who never wrote a
     * profile fasts by default but has no row: nothing to remind them yet.
     *
     * @return list<HealthProfile>
     */
    public function findFasting(): array
    {
        /* @var list<HealthProfile> */
        return $this->createQueryBuilder('p')
            ->join('p.user', 'u')->addSelect('u')
            ->where('p.fastingProtocol IS NOT NULL')
            ->getQuery()
            ->getResult();
    }
}
