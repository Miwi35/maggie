<?php

declare(strict_types=1);

namespace Maggie\Notification\Repository;

use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

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
     * Check if a reminder notification already exists for a given event + minutes combo.
     */
    public function reminderExists(string $eventIri, int $minutes): bool
    {
        $result = $this->createQueryBuilder('n')
            ->select('COUNT(n.id)')
            ->where('n.relatedEntityIri = :iri')
            ->andWhere('n.body = :body')
            ->andWhere('n.type = :type')
            ->setParameter('iri', $eventIri)
            ->setParameter('body', (string) $minutes)
            ->setParameter('type', \Maggie\Notification\Entity\NotificationType::Reminder)
            ->getQuery()
            ->getSingleScalarResult();

        return (int) $result > 0;
    }
}
