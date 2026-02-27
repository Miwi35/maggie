<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Store;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Store> */
class StoreRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Store::class);
    }

    /** @return Store[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['visitOrder' => 'ASC']);
    }

    public function findByNameAndUser(string $name, User $user): ?Store
    {
        return $this->findOneBy(['name' => $name, 'user' => $user]);
    }
}
