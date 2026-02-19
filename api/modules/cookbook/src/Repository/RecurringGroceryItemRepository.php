<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Maggie\Cookbook\Enum\RecurringFrequency;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<RecurringGroceryItem> */
class RecurringGroceryItemRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, RecurringGroceryItem::class);
    }

    /** @return RecurringGroceryItem[] */
    public function findByFrequency(RecurringFrequency $frequency, User $user): array
    {
        return $this->findBy(['frequency' => $frequency, 'user' => $user]);
    }

    /** @return RecurringGroceryItem[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user]);
    }
}
