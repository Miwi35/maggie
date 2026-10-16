<?php

declare(strict_types=1);

namespace Maggie\Grocery\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Errand;

/**
 * @extends ServiceEntityRepository<Errand>
 */
class ErrandRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Errand::class);
    }

    /**
     * The user's errands, most recent first. `$storeName` matches the store's
     * name as it was when the errand ended, case-insensitively and in part
     * (« carrefour » finds « Carrefour Market »).
     *
     * @return list<Errand>
     */
    public function findLatestForUser(User $user, ?string $storeName = null, int $limit = 5): array
    {
        $qb = $this->createQueryBuilder('e')
            ->where('e.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('e.endedAt', 'DESC')
            ->addOrderBy('e.id', 'DESC')
            ->setMaxResults($limit);

        if (null !== $storeName && '' !== trim($storeName)) {
            // The name is a name, not a pattern: `%` and `_` are taken literally.
            $needle = addcslashes(mb_strtolower(trim($storeName)), '%_\\');
            $qb->andWhere("LOWER(e.storeName) LIKE :store ESCAPE '\\'")
                ->setParameter('store', '%'.$needle.'%');
        }

        /** @var list<Errand> $errands */
        $errands = $qb->getQuery()->getResult();

        return $errands;
    }
}
