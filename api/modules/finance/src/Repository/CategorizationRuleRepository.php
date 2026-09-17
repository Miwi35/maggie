<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\CategorizationRule;

/** @extends ServiceEntityRepository<CategorizationRule> */
class CategorizationRuleRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, CategorizationRule::class);
    }

    /** @return CategorizationRule[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['priority' => 'DESC', 'id' => 'ASC']);
    }

    /**
     * Active rules in the order they get their say: highest priority first,
     * then oldest first (ULIDs sort by creation time).
     *
     * @return CategorizationRule[]
     */
    public function findActiveForUser(User $user): array
    {
        return $this->findBy(
            ['user' => $user, 'isActive' => true],
            ['priority' => 'DESC', 'id' => 'ASC'],
        );
    }
}
