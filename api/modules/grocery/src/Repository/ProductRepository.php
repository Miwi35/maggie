<?php

declare(strict_types=1);

namespace Maggie\Grocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;

/** @extends ServiceEntityRepository<Product> */
class ProductRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Product::class);
    }

    /**
     * @return Product[]
     */
    public function findByUser(User $user): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('p.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Product[] */
    public function searchByName(User $user, string $query): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('LOWER(p.name) LIKE LOWER(:query)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('query', '%' . $query . '%')
            ->orderBy('p.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Product[] */
    public function findByCategory(ProductCategory $category): array
    {
        return $this->findBy(['category' => $category], ['name' => 'ASC']);
    }
}
