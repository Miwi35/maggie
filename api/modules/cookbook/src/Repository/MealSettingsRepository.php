<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<MealSettings> */
class MealSettingsRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, MealSettings::class);
    }

    public function findOneByUser(User $user): ?MealSettings
    {
        return $this->findOneBy(['user' => $user]);
    }

    /** The user's default, without laying the settings down: a read must not write. */
    public function defaultGuestsOf(User $user): int
    {
        return $this->findOneByUser($user)?->getDefaultGuests() ?? MealSettings::DEFAULT_GUESTS;
    }
}
