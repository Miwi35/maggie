<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Contact;

/** @extends ServiceEntityRepository<Contact> */
class ContactRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Contact::class);
    }

    /** @return Contact[] by name */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['name' => 'ASC', 'id' => 'ASC']);
    }

    /**
     * Contacts whose name contains the query, accents and case aside — the
     * picker's search, and the MCP's when Elasticsearch is not asked.
     *
     * @return Contact[]
     */
    public function searchByUser(User $user, string $query, int $limit = 20): array
    {
        return $this->createQueryBuilder('c')
            ->where('c.user = :user')
            ->andWhere('LOWER(c.name) LIKE :query')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('query', '%'.mb_strtolower(trim($query)).'%')
            ->orderBy('c.name', 'ASC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }

    public function findOneOwned(string $id, User $user): ?Contact
    {
        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }
}
