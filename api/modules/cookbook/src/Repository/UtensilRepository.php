<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Utensil> */
class UtensilRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Utensil::class);
    }

    /** @return Utensil[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['name' => 'ASC']);
    }

    public function findOneForUser(string $id, User $user): ?Utensil
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }

    /** The user's utensil of that name, whatever its case and the spaces around it. */
    public function findByNameAndUser(string $name, User $user): ?Utensil
    {
        return $this->createQueryBuilder('u')
            ->where('LOWER(u.name) = LOWER(:name)')
            ->andWhere('u.user = :user')
            ->setParameter('name', Utensil::normalizeName($name))
            ->setParameter('user', $user->getId(), 'ulid')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }
}
