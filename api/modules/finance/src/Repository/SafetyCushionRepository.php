<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\SafetyCushion;

/** @extends ServiceEntityRepository<SafetyCushion> */
class SafetyCushionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, SafetyCushion::class);
    }

    public function findOneByUser(User $user): ?SafetyCushion
    {
        return $this->findOneBy(['user' => $user]);
    }
}
