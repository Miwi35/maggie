<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Place;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Place> */
class PlaceRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Place::class);
    }

    /** @return list<Place> the user's places, home first, then by name */
    public function findByUser(User $user): array
    {
        /* @var list<Place> */
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('p.isHome', 'DESC')
            ->addOrderBy('p.name', 'ASC')
            ->addOrderBy('p.createdAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return list<Place> the user's places that have coordinates */
    public function findLocatedByUser(User $user): array
    {
        /* @var list<Place> */
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('p.latitude IS NOT NULL')
            ->andWhere('p.longitude IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    /** @return list<Place> the user's homes — one at most once saved through the bus */
    public function findHomes(User $user): array
    {
        /* @var list<Place> */
        return $this->createQueryBuilder('p')
            ->where('p.user = :user')
            ->andWhere('p.isHome = true')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    /** The user's place, or null — another user's is not found either. */
    public function findOwned(User $user, string $id): ?Place
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->createQueryBuilder('p')
            ->where('p.id = :id')
            ->andWhere('p.user = :user')
            ->setParameter('id', Ulid::fromString($id), 'ulid')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getOneOrNullResult();
    }
}
