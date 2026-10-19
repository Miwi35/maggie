<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<TaskReminder> */
class TaskReminderRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, TaskReminder::class);
    }

    public function findOwned(User $user, string $id): ?TaskReminder
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->findOneBy(['id' => Ulid::fromString($id), 'user' => $user]);
    }

    /**
     * Whether a reminder of that kind, not put off by the user, was planned in
     * [$from, $to[ — on that subject or at that moment when given: once per day.
     */
    public function isPlanned(User $user, string $kind, \DateTimeImmutable $from, \DateTimeImmutable $to, ?string $subjectId = null, ?DayMoment $moment = null): bool
    {
        $query = $this->createQueryBuilder('r')
            ->select('r.id')
            ->where('r.user = :user')
            ->andWhere('r.kind = :kind')
            ->andWhere('r.snoozed = false')
            ->andWhere('r.scheduledFor >= :from')
            ->andWhere('r.scheduledFor < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('kind', $kind)
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->setMaxResults(1);
        if (null !== $subjectId) {
            $query->andWhere('r.subjectId = :subject')->setParameter('subject', $subjectId);
        }
        if (null !== $moment) {
            $query->andWhere('r.moment = :moment')->setParameter('moment', $moment->value);
        }

        return null !== $query->getQuery()->getOneOrNullResult();
    }

    /**
     * The user's reminders due by $now and neither sent nor dropped, in the
     * order they were planned.
     *
     * @return list<TaskReminder>
     */
    public function findPending(User $user, \DateTimeImmutable $now): array
    {
        /* @var list<TaskReminder> */
        return $this->createQueryBuilder('r')
            ->where('r.user = :user')
            ->andWhere('r.sentAt IS NULL')
            ->andWhere('r.droppedAt IS NULL')
            ->andWhere('r.scheduledFor <= :now')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('now', $now, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('r.scheduledFor', 'ASC')
            ->addOrderBy('r.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** The reminders sent in [$from, $to[ that count toward the daily cap: those the user did not put off. */
    public function countCappedSentBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): int
    {
        return (int) $this->createQueryBuilder('r')
            ->select('COUNT(r.id)')
            ->where('r.user = :user')
            ->andWhere('r.snoozed = false')
            ->andWhere('r.sentAt >= :from')
            ->andWhere('r.sentAt < :to')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE)
            ->getQuery()
            ->getSingleScalarResult();
    }

    /** The last message key sent for that kind: the next one is drawn without it. */
    public function findLastMessageKey(User $user, string $kind): ?string
    {
        $row = $this->createQueryBuilder('r')
            ->select('r.messageKey')
            ->where('r.user = :user')
            ->andWhere('r.kind = :kind')
            ->andWhere('r.sentAt IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('kind', $kind)
            ->orderBy('r.sentAt', 'DESC')
            ->addOrderBy('r.id', 'DESC')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();

        return \is_array($row) ? $row['messageKey'] : null;
    }
}
