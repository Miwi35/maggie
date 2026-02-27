<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\CiqualFood;

/** @extends ServiceEntityRepository<CiqualFood> */
class CiqualFoodRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, CiqualFood::class);
    }

    /** @return CiqualFood[] */
    public function searchByName(string $query): array
    {
        return $this->createQueryBuilder('f')
            ->where('LOWER(f.alimNameFr) LIKE LOWER(:query)')
            ->setParameter('query', '%' . $query . '%')
            ->orderBy('f.alimNameFr', 'ASC')
            ->setMaxResults(50)
            ->getQuery()
            ->getResult();
    }

    public function findByAlimCode(string $alimCode): ?CiqualFood
    {
        return $this->findOneBy(['alimCode' => $alimCode]);
    }
}
