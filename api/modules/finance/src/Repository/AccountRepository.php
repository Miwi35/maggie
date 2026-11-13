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

    /**
     * The accounts a bank created that a hand-typed account can be linked to:
     * linked to a bank, same currency, not closed (MAG-823).
     *
     * @return Account[]
     */
    public function findLinkCandidates(User $user, Account $account): array
    {
        if (null !== $account->getBankConnection()) {
            return [];
        }

        return $this->createQueryBuilder('a')
            ->where('a.user = :user')
            ->andWhere('a.bankConnection IS NOT NULL')
            ->andWhere('a.currency = :currency')
            ->andWhere('a.closedAt IS NULL')
            ->andWhere('a.id != :id')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('currency', $account->getCurrency())
            ->setParameter('id', $account->getId(), 'ulid')
            ->orderBy('a.name', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
