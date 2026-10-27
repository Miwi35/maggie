<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Core\Entity\User;

/** @extends ServiceEntityRepository<Recipe> */
class RecipeRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Recipe::class);
    }

    public function findOneForUser(string $id, User $user): ?Recipe
    {
        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }

    /**
     * Every recipe of the user with its lines and their products, in one query: the table of
     * recipes reads what each one brings per serving.
     *
     * @return Recipe[]
     */
    public function findAllWithIngredientsForUser(User $user): array
    {
        return $this->createQueryBuilder('r')
            ->leftJoin('r.ingredients', 'ri')->addSelect('ri')
            ->leftJoin('ri.ingredient', 'p')->addSelect('p')
            ->where('r.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Recipe[] */
    public function searchByName(User $user, string $query): array
    {
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->andWhere('LOWER(r.name) LIKE LOWER(:query)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('query', '%'.$query.'%')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Recipe[] */
    public function searchByTags(User $user, string $tag): array
    {
        // `tags` is a JSON column: Postgres has no LIKE operator on json, so match in PHP.
        $recipes = $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('r.name', 'ASC')
            ->getQuery()
            ->getResult();

        return array_values(array_filter($recipes, static fn (Recipe $recipe) => in_array($tag, $recipe->getTags(), true)));
    }

    /** @return Recipe[] the recipes made with that utensil */
    public function findByUtensil(Utensil $utensil): array
    {
        return $this->createQueryBuilder('r')
            ->join('r.utensils', 'u')
            ->where('u = :utensil')
            ->setParameter('utensil', $utensil->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }
}
