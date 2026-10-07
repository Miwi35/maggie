<?php

declare(strict_types=1);

namespace Maggie\Notification\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;

/**
 * @extends ServiceEntityRepository<Notification>
 */
class NotificationRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Notification::class);
    }

    /**
     * @return Notification[]
     */
    public function findUnreadByUser(User $user): array
    {
        return $this->createQueryBuilder('n')
            ->where('n.user = :user')
            ->andWhere('n.readAt IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('n.createdAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * @return Notification[]
     */
    public function findByUser(User $user, int $limit = 50): array
    {
        return $this->createQueryBuilder('n')
            ->where('n.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('n.createdAt', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }

    /**
     * Whether a reminder was already sent for one occurrence of an event, at one delay.
     *
     * The occurrence is part of the key, not a detail: a recurring event is a
     * single row, so (event, minutes) alone identifies the series and the cron
     * would send one reminder for the whole thing (MAG-121). Rows written before
     * the column existed carry no occurrence and never match, so a reminder
     * already sent for an event in the next 24 hours may repeat once after the
     * deploy — see the migration.
     */
    public function reminderExists(string $eventIri, int $minutes, \DateTimeImmutable $occurrenceStartAt): bool
    {
        $result = $this->createQueryBuilder('n')
            ->select('COUNT(n.id)')
            ->where('n.relatedEntityIri = :iri')
            ->andWhere('n.body = :body')
            ->andWhere('n.type = :type')
            ->andWhere('n.occurrenceStartAt = :occurrence')
            ->setParameter('iri', $eventIri)
            ->setParameter('body', (string) $minutes)
            ->setParameter('type', NotificationType::Reminder)
            ->setParameter('occurrence', $occurrenceStartAt, Types::DATETIMETZ_IMMUTABLE)
            ->getQuery()
            ->getSingleScalarResult();

        return (int) $result > 0;
    }

    /** Whether this user was already told about this entity since the given moment. */
    public function existsSince(NotificationType $type, string $relatedEntityIri, \DateTimeImmutable $since): bool
    {
        $result = $this->createQueryBuilder('n')
            ->select('COUNT(n.id)')
            ->where('n.type = :type')
            ->andWhere('n.relatedEntityIri = :iri')
            ->andWhere('n.createdAt >= :since')
            ->setParameter('type', $type)
            ->setParameter('iri', $relatedEntityIri)
            ->setParameter('since', $since)
            ->getQuery()
            ->getSingleScalarResult();

        return (int) $result > 0;
    }
}
