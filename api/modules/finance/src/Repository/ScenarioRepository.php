<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Scenario;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Scenario> */
class ScenarioRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Scenario::class);
    }

    /** @return list<Scenario> oldest first */
    public function findByUser(User|string $user): array
    {
        return $this->findBy(['user' => $user], ['id' => 'ASC']);
    }

    /** The user's scenario, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $scenarioId): ?Scenario
    {
        if (!Ulid::isValid($scenarioId)) {
            return null;
        }

        return $this->findOneBy(['id' => $scenarioId, 'user' => $user]);
    }

    /** The scenario the user follows, if they chose one. */
    public function findCurrent(User|string $user): ?Scenario
    {
        return $this->findOneBy(['user' => $user, 'current' => true]);
    }

    /** The scenario materialized from an automatic one (`rapid`, `balanced`, `quality`). */
    public function findOneByAutoKey(User|string $user, string $autoKey): ?Scenario
    {
        return $this->findOneBy(['user' => $user, 'autoKey' => $autoKey]);
    }
}
