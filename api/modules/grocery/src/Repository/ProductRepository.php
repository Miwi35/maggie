<?php

declare(strict_types=1);

namespace Maggie\Grocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
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
            ->setParameter('query', '%'.$query.'%')
            ->orderBy('p.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The user's product a free label names, ignoring case, accents and a simple plural
     * (« poireaux » finds « Poireau »); an exact spelling wins over a plural match.
     */
    public function findByLabel(User $user, string $label): ?Product
    {
        $exact = self::labelKey($label, false);
        $loose = self::labelKey($label, true);
        $plural = null;
        foreach ($this->findByUser($user) as $product) {
            if (self::labelKey($product->getName(), false) === $exact) {
                return $product;
            }
            if (null === $plural && self::labelKey($product->getName(), true) === $loose) {
                $plural = $product;
            }
        }

        return $plural;
    }

    private static function labelKey(string $label, bool $singular): string
    {
        $key = strtr(mb_strtolower(trim($label)), [
            'à' => 'a', 'â' => 'a', 'ä' => 'a', 'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
            'î' => 'i', 'ï' => 'i', 'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u',
            'ç' => 'c', 'ÿ' => 'y', 'œ' => 'oe', 'æ' => 'ae',
        ]);
        $words = preg_split('/\s+/u', $key) ?: [$key];
        if ($singular) {
            $words = array_map(static fn (string $w): string => mb_strlen($w) > 3 ? rtrim($w, 'sx') : $w, $words);
        }

        return implode(' ', $words);
    }

    /** @return Product[] */
    public function findByCategory(ProductCategory $category): array
    {
        return $this->findBy(['category' => $category], ['name' => 'ASC']);
    }

    /** @return Product[] */
    public function findByStore(Store $store): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.preferredStore = :store OR p.fallbackStore = :store')
            ->setParameter('store', $store->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }
}
