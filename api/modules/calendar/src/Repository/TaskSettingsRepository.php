<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<TaskSettings> */
class TaskSettingsRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, TaskSettings::class);
    }

    public function findOneByUser(User $user): ?TaskSettings
    {
        return $this->findOneBy(['user' => $user]);
    }
}
