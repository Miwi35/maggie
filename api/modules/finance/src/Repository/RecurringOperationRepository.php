<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;

/** @extends ServiceEntityRepository<RecurringOperation> */
class RecurringOperationRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, RecurringOperation::class);
    }

    /**
     * A user's series, in a stable order: by label, then oldest first.
     *
     * @return RecurringOperation[]
     */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['label' => 'ASC', 'id' => 'ASC']);
    }
}
