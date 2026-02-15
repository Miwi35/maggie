<?php

declare(strict_types=1);

namespace Maggie\Memory\Repository;

use Maggie\Memory\Entity\Memory;
use Maggie\Memory\Entity\MemoryType;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Memory>
 */
class MemoryRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Memory::class);
    }

    /**
     * Full-text search using PostgreSQL ts_rank + plainto_tsquery.
     *
     * @return Memory[]
     */
    public function search(string $query, ?MemoryType $type = null, ?string $userId = null, int $limit = 20): array
    {
        $conn = $this->getEntityManager()->getConnection();

        $sql = "SELECT m.id, ts_rank(to_tsvector('french', m.content), plainto_tsquery('french', :query)) AS rank"
            . ' FROM memory m'
            . " WHERE to_tsvector('french', m.content) @@ plainto_tsquery('french', :query)";
        $params = ['query' => $query];

        if ($type !== null) {
            $sql .= ' AND m.type = :type';
            $params['type'] = $type->value;
        }
        if ($userId !== null) {
            $sql .= ' AND m.user_id = :userId';
            $params['userId'] = $userId;
        }

        $sql .= ' ORDER BY rank DESC LIMIT :limit';
        $params['limit'] = $limit;

        $rows = $conn->fetchAllAssociative($sql, $params);
        $ids = array_column($rows, 'id');

        if (empty($ids)) {
            return [];
        }

        return $this->createQueryBuilder('m')
            ->where('m.id IN (:ids)')
            ->setParameter('ids', $ids)
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Memory[]
     */
    public function findAllFactual(?string $userId = null): array
    {
        $qb = $this->createQueryBuilder('m')
            ->where('m.type = :type')
            ->setParameter('type', MemoryType::Factual)
            ->orderBy('m.createdAt', 'ASC');

        if ($userId !== null) {
            $qb->andWhere('m.user = :userId')
                ->setParameter('userId', $userId);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * @return Memory[]
     */
    public function findRecentEpisodic(?string $userId = null, int $limit = 20): array
    {
        $qb = $this->createQueryBuilder('m')
            ->where('m.type = :type')
            ->setParameter('type', MemoryType::Episodic)
            ->orderBy('m.createdAt', 'DESC')
            ->setMaxResults($limit);

        if ($userId !== null) {
            $qb->andWhere('m.user = :userId')
                ->setParameter('userId', $userId);
        }

        return $qb->getQuery()->getResult();
    }
}
