<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Ingredient> */
class IngredientRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Ingredient::class);
    }

    public function findOneByUserAndCiqualAlimCode(User $user, string $alimCode): ?Ingredient
    {
        return $this->createQueryBuilder('i')
            ->where('i.user = :user')
            ->andWhere('i.ciqualAlimCode = :alimCode')
            ->setParameter('user', $user)
            ->setParameter('alimCode', $alimCode)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** @return Ingredient[] */
    public function searchByName(User $user, string $query): array
    {
        return $this->createQueryBuilder('i')
            ->where('i.user = :user')
            ->andWhere('LOWER(i.name) LIKE LOWER(:query)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('query', '%' . $query . '%')
            ->orderBy('i.name', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
