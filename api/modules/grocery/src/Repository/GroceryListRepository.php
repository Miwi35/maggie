<?php

declare(strict_types=1);

namespace Maggie\Grocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryList;

/** @extends ServiceEntityRepository<GroceryList> */
class GroceryListRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, GroceryList::class);
    }

    public function findOrCreateForUser(User $user): GroceryList
    {
        $list = $this->findOneBy(['user' => $user]);

        if (null === $list) {
            $list = new GroceryList();
            $list->setUser($user);
            $this->getEntityManager()->persist($list);
            $this->getEntityManager()->flush();
        }

        return $list;
    }
}
