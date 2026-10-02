<?php

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Entity\User;

/**
 * @extends ServiceEntityRepository<Agenda>
 */
class AgendaRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Agenda::class);
    }

    /**
     * The agenda the user marked as default, or null — never a guess. The
     * alphabetical fallback this used to have filed Maggie's appointments in
     * whichever agenda sorted first (MAG-149).
     */
    public function findDefault(User $user): ?Agenda
    {
        return $this->findOneBy(['user' => $user, 'isDefault' => true]);
    }

    /**
     * @return Agenda[] the user's default agendas other than `$keep`
     */
    public function findDefaultsExcept(User $user, Agenda $keep): array
    {
        return $this->createQueryBuilder('a')
            ->where('a.user = :user')
            ->andWhere('a.isDefault = true')
            ->andWhere('a.id != :keep')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('keep', $keep->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    public function userHasAgenda(User $user): bool
    {
        return null !== $this->findOneBy(['user' => $user]);
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

    /**
     * The agenda already connected to a Google calendar, if any (MAG-148).
     *
     * A unique index keeps a user from holding two of them, but this query is
     * also what runs against a database the cleanup migration has not reached
     * yet, so it answers with the oldest — ULIDs sort by creation time, and
     * the oldest is the one the migration keeps.
     */
    public function findOneByGoogleCalendarId(User $user, string $googleCalendarId): ?Agenda
    {
        return $this->createQueryBuilder('a')
            ->where('a.user = :user')
            ->andWhere('a.googleCalendarId = :googleCalendarId')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('googleCalendarId', $googleCalendarId)
            ->orderBy('a.id', 'ASC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
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

    /**
     * Google-linked agendas whose last successful pull is older than
     * `$before`, or that were never pulled.
     *
     * @return Agenda[]
     */
    public function findGoogleSyncedNotSyncedSince(\DateTimeImmutable $before): array
    {
        return $this->createQueryBuilder('a')
            ->where('a.googleCalendarId IS NOT NULL')
            ->andWhere('a.lastGoogleSyncAt IS NULL OR a.lastGoogleSyncAt < :before')
            ->setParameter('before', $before)
            ->orderBy('a.name', 'ASC')
            ->getQuery()
            ->getResult();
    }
}
