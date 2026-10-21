<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Placement;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Placement> */
class PlacementRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Placement::class);
    }

    /** @return list<Placement> by name */
    public function findByUser(User|string $user): array
    {
        return $this->findBy(['user' => $user], ['name' => 'ASC']);
    }

    /** The user's placement, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $placementId): ?Placement
    {
        if (!Ulid::isValid($placementId)) {
            return null;
        }

        return $this->findOneBy(['id' => $placementId, 'user' => $user]);
    }
}
