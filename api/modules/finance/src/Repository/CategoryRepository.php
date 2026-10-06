<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;

/** @extends ServiceEntityRepository<Category> */
class CategoryRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Category::class);
    }

    /** @return Category[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['name' => 'ASC']);
    }

    /** @return Category[] */
    public function findRootsByUser(User $user): array
    {
        return $this->findBy(['user' => $user, 'parent' => null], ['name' => 'ASC']);
    }

    /**
     * The categories the user declared as rentes. The independence counter
     * needs them to tell "no rente declared yet" — where the user has
     * something to do — from "declared, nothing came in this quarter".
     *
     * @return Category[]
     */
    public function findPassiveIncomeByUser(User $user): array
    {
        return $this->findBy(['user' => $user, 'passiveIncome' => true], ['name' => 'ASC']);
    }

    /** @return Category[] */
    public function findChildren(Category $parent): array
    {
        return $this->findBy(['parent' => $parent], ['name' => 'ASC']);
    }
}
