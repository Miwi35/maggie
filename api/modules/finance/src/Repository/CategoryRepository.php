<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Symfony\Component\Uid\Ulid;

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

    /** @return list<Category> every category below this one, whatever the depth */
    public function findDescendants(Category $category): array
    {
        $childrenByParent = [];
        foreach ($this->findBy(['user' => $category->getUser()]) as $candidate) {
            if (null !== $candidate->getParent()) {
                $childrenByParent[(string) $candidate->getParent()->getId()][] = $candidate;
            }
        }

        $descendants = [];
        $queue = [(string) $category->getId()];
        while ([] !== $queue) {
            foreach ($childrenByParent[array_shift($queue)] ?? [] as $child) {
                $descendants[] = $child;
                $queue[] = (string) $child->getId();
            }
        }

        return $descendants;
    }

    /**
     * The category and every category below it, as ULIDs, for a filter that
     * must take the sub-categories along.
     *
     * @return list<Ulid>
     */
    public function findSelfAndDescendantIds(Category $category): array
    {
        return [$category->getId(), ...array_map(static fn (Category $c) => $c->getId(), $this->findDescendants($category))];
    }
}
