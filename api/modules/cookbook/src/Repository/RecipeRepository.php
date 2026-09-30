<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Recipe;
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

    /** @return Recipe[] */
    public function searchByName(User $user, string $query): array
    {
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->andWhere('LOWER(r.name) LIKE LOWER(:query)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('query', '%' . $query . '%')
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
}
