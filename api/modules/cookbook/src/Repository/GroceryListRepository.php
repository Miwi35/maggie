<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryListStatus;

/** @extends ServiceEntityRepository<GroceryList> */
class GroceryListRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, GroceryList::class);
    }

    public function findActive(): ?GroceryList
    {
        return $this->findOneBy(['status' => GroceryListStatus::Active], ['createdAt' => 'DESC']);
    }
}
