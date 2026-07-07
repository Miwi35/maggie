<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;

/** @extends ServiceEntityRepository<Account> */
class AccountRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Account::class);
    }

    /** @return Account[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['name' => 'ASC']);
    }

    public function findByNameAndUser(string $name, User $user): ?Account
    {
        return $this->createQueryBuilder('a')
            ->where('LOWER(a.name) = LOWER(:name)')
            ->andWhere('a.user = :user')
            ->setParameter('name', $name)
            ->setParameter('user', $user->getId(), 'ulid')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
