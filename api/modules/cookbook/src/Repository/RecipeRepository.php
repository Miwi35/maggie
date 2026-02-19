<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Recipe;

/** @extends ServiceEntityRepository<Recipe> */
class RecipeRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Recipe::class);
    }

    /** @return Recipe[] */
    public function searchByName(string $query): array
    {
        return $this->createQueryBuilder('r')
            ->where('LOWER(r.name) LIKE LOWER(:query)')
            ->setParameter('query', '%' . $query . '%')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Recipe[] */
    public function searchByTags(string $tag): array
    {
        return $this->createQueryBuilder('r')
            ->where('r.tags LIKE :tag')
            ->setParameter('tag', '%"' . $tag . '"%')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
