<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\CiqualFood;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Ingredient> */
class IngredientRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Ingredient::class);
    }

    public function findOneByUserAndCiqualFood(User $user, CiqualFood $ciqualFood): ?Ingredient
    {
        return $this->createQueryBuilder('i')
            ->where('i.user = :user')
            ->andWhere('i.ciqualFood = :ciqualFood')
            ->setParameter('user', $user)
            ->setParameter('ciqualFood', $ciqualFood)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** @return Ingredient[] */
    public function searchByName(string $query): array
    {
        return $this->createQueryBuilder('i')
            ->where('LOWER(i.name) LIKE LOWER(:query)')
            ->setParameter('query', '%' . $query . '%')
            ->orderBy('i.name', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
