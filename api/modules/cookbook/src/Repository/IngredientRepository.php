<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
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
            ->setParameter('user', $user->getId(), 'ulid')
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
            ->setParameter('query', '%'.$query.'%')
            ->orderBy('i.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return list<string> the names of the recipes that list the ingredient */
    public function findRecipeNamesUsing(Ingredient $ingredient): array
    {
        $rows = $this->getEntityManager()->createQueryBuilder()
            ->select('DISTINCT r.name')
            ->from(Recipe::class, 'r')
            ->join(RecipeIngredient::class, 'ri', 'WITH', 'ri.recipe = r')
            ->where('ri.ingredient = :ingredient')
            ->setParameter('ingredient', $ingredient->getId(), 'ulid')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getSingleColumnResult();

        return array_values(array_map('strval', $rows));
    }
}
