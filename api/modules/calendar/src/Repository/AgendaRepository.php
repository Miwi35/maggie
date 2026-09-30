<?php

namespace Maggie\Calendar\Repository;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Entity\User;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Agenda>
 */
class AgendaRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Agenda::class);
    }

    public function findDefault(User $user): ?Agenda
    {
        return $this->findOneBy(['user' => $user, 'isDefault' => true])
            ?? $this->findOneBy(['user' => $user], ['name' => 'ASC']);
    }

    /**
     * @return Agenda[]
     */
    public function findByUser(User $user): array
    {
        return $this->createQueryBuilder('a')
            ->where('a.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('a.name', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Agenda[]
     */
    public function findGoogleSyncedByUser(User $user): array
    {
        return $this->createQueryBuilder('a')
            ->where('a.user = :user')
            ->andWhere('a.googleCalendarId IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    public function findByGoogleWatchChannelId(string $channelId): ?Agenda
    {
        return $this->findOneBy(['googleWatchChannelId' => $channelId]);
    }

    /**
     * @return Agenda[]
     */
    public function findWithExpiringWatchChannels(\DateTimeImmutable $before): array
    {
        return $this->createQueryBuilder('a')
            ->where('a.googleWatchChannelId IS NOT NULL')
            ->andWhere('a.googleWatchExpiresAt IS NOT NULL')
            ->andWhere('a.googleWatchExpiresAt < :before')
            ->setParameter('before', $before)
            ->getQuery()
            ->getResult();
    }
}
