<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Routine;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Routine> */
class RoutineRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Routine::class);
    }

    /** @return list<Routine> */
    public function findByUser(User $user): array
    {
        /* @var list<Routine> */
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    /** The user's routine, or null — another user's is not found either. */
    public function findOwned(User $user, string $id): ?Routine
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->createQueryBuilder('r')
            ->where('r.id = :id')
            ->andWhere('r.user = :user')
            ->setParameter('id', Ulid::fromString($id), 'ulid')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();
    }
}
