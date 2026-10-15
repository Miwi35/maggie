<?php

declare(strict_types=1);

namespace Maggie\Grocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Store;

/** @extends ServiceEntityRepository<Aisle> */
class AisleRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Aisle::class);
    }

    /** @return Aisle[] in the order of the way through the store */
    public function findByStore(Store $store): array
    {
        return $this->findBy(['store' => $store], ['position' => 'ASC', 'name' => 'ASC']);
    }

    public function findOneByStoreAndName(Store $store, string $name): ?Aisle
    {
        return $this->createQueryBuilder('a')
            ->where('a.store = :store')
            ->andWhere('LOWER(a.name) = LOWER(:name)')
            ->setParameter('store', $store->getId(), 'ulid')
            ->setParameter('name', $name)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
