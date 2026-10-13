<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\TreasurySettings;

/** @extends ServiceEntityRepository<TreasurySettings> */
class TreasurySettingsRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, TreasurySettings::class);
    }

    public function findOneByUser(User $user): ?TreasurySettings
    {
        return $this->findOneBy(['user' => $user]);
    }
}
