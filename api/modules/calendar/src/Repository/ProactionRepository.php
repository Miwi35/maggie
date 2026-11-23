<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ParameterType;
use Doctrine\DBAL\Types\Type;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Proaction> */
class ProactionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Proaction::class);
    }

    /**
     * A user's proactions by start, within an optional window and status.
     *
     * @return Proaction[]
     */
    public function findForUser(User $user, ?\DateTimeImmutable $from = null, ?\DateTimeImmutable $to = null, ?ProactionStatus $status = null): array
    {
        $qb = $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('p.rrule IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('p.startAt', 'ASC');

        if (null !== $from) {
            $qb->andWhere('p.startAt >= :from')->setParameter('from', $from, Types::DATETIMETZ_IMMUTABLE);
        }
        if (null !== $to) {
            $qb->andWhere('p.startAt <= :to')->setParameter('to', $to, Types::DATETIMETZ_IMMUTABLE);
        }
        if (null !== $status) {
            $qb->andWhere('p.executionStatus = :status')->setParameter('status', $status->value);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The user's series, by first start.
     *
     * @return Proaction[]
     */
    public function findSeriesForUser(User $user): array
    {
        return $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('p.rrule IS NOT NULL')
            ->andWhere('p.status <> :cancelled')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('cancelled', EventStatus::Cancelled->value)
            ->orderBy('p.startAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** @return Proaction[] */
    public function findInstancesOf(Proaction $series): array
    {
        return $this->createQueryBuilder('p')
            ->where('p.recurringEvent = :series')
            ->setParameter('series', $series->getId(), 'ulid')
            ->getQuery()
            ->getResult();
    }

    /** The instance that holds this occurrence of the series, cancelled or not. */
    public function findInstanceAt(Proaction $series, \DateTimeImmutable $occurrence): ?Proaction
    {
        return $this->createQueryBuilder('p')
            ->where('p.recurringEvent = :series')
            ->andWhere('p.originalStartAt = :at')
            ->setParameter('series', $series->getId(), 'ulid')
            ->setParameter('at', $occurrence, Types::DATETIMETZ_IMMUTABLE)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** The user's pending proaction with this prompt at this very instant, if there is one. */
    public function findPendingTwin(User $user, \DateTimeImmutable $at, string $prompt, ?string $rrule = null): ?Proaction
    {
        $qb = $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('p.startAt = :at')
            ->andWhere('p.prompt = :prompt')
            ->andWhere('p.executionStatus = :pending')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('at', $at, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter('prompt', $prompt)
            ->setParameter('pending', ProactionStatus::Pending->value)
            ->setMaxResults(1);

        if (null === $rrule) {
            $qb->andWhere('p.rrule IS NULL');
        } else {
            $qb->andWhere('p.rrule = :rrule')->setParameter('rrule', $rrule);
        }

        return $qb->getQuery()->getOneOrNullResult();
    }

    /** Whether the user has a proaction with this summary still waiting for its run. */
    public function hasPendingWithSummary(User $user, string $summary): bool
    {
        return null !== $this->createQueryBuilder('p')
            ->select('p.id')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('p.summary = :summary')
            ->andWhere('p.executionStatus = :pending')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('summary', $summary)
            ->setParameter('pending', ProactionStatus::Pending->value)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The topics the user was last told about since `$since`, newest first: one
     * proaction per topic, the latest delivered as an interruption or in the
     * thread — a run that said nothing told nothing (MAG-886).
     *
     * @return Proaction[]
     */
    public function findSignaledTopics(User $user, \DateTimeImmutable $since, int $limit = 20): array
    {
        /** @var Proaction[] $delivered */
        $delivered = $this->createQueryBuilder('p')
            ->join('p.agenda', 'a')
            ->where('a.user = :user')
            ->andWhere('p.topicKey IS NOT NULL')
            ->andWhere('p.delivery IN (:signaled)')
            ->andWhere('p.completedAt >= :since')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('signaled', [ProactionDelivery::Interrupt->value, ProactionDelivery::Thread->value])
            ->setParameter('since', $since, Types::DATETIMETZ_IMMUTABLE)
            ->orderBy('p.completedAt', 'DESC')
            ->addOrderBy('p.id', 'DESC')
            ->getQuery()
            ->getResult();

        $latest = [];
        foreach ($delivered as $proaction) {
            $latest[$proaction->getTopicKey()] ??= $proaction;
        }

        return array_slice(array_values($latest), 0, $limit);
    }

    /** The proaction, if it is the user's — or anyone's when no user is named: another user's is not found. */
    public function findOneForUser(string $id, ?User $user): ?Proaction
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        $qb = $this->createQueryBuilder('p')
            ->where('p.id = :id')
            ->setParameter('id', Ulid::fromString($id), 'ulid');

        if (null !== $user) {
            $qb->join('p.agenda', 'a')->andWhere('a.user = :user')->setParameter('user', $user->getId(), 'ulid');
        }

        return $qb->getQuery()->getOneOrNullResult();
    }

    /**
     * Locks and returns the ids of the pending proactions whose start has come, oldest first.
     *
     * Must run inside a transaction, which the caller closes once it has marked
     * them running. `SKIP LOCKED` is what keeps two executors apart: the rows one
     * of them holds are invisible to the other, and once it commits they are no
     * longer `pending` — Postgres re-reads a row it waited on before keeping it.
     *
     * With `$id`, that pending proaction only, its start come or not.
     *
     * @return Ulid[]
     */
    public function lockDueIds(\DateTimeImmutable $now, ?User $user, int $limit, ?Ulid $id = null): array
    {
        $connection = $this->getEntityManager()->getConnection();

        $sql = 'SELECT p.id FROM proaction p'
            .' JOIN event e ON e.id = p.id'
            .' JOIN agenda a ON a.id = e.agenda_id'
            .' WHERE p.execution_status = :pending AND e.rrule IS NULL AND e.status <> \'cancelled\''
            .(null !== $id ? ' AND p.id = :id' : ' AND e.start_at <= :now')
            .(null !== $user ? ' AND a.user_id = :user' : '')
            .' ORDER BY e.start_at ASC, p.id ASC'
            .' LIMIT :limit'
            .' FOR UPDATE OF p SKIP LOCKED';

        $params = ['pending' => ProactionStatus::Pending->value, 'limit' => $limit];
        $types = ['limit' => ParameterType::INTEGER];
        if (null !== $id) {
            $params['id'] = $id;
            $types['id'] = 'ulid';
        } else {
            $params['now'] = $now;
            $types['now'] = Types::DATETIMETZ_IMMUTABLE;
        }
        if (null !== $user) {
            $params['user'] = $user->getId();
            $types['user'] = 'ulid';
        }

        $ulid = Type::getType('ulid');
        $platform = $connection->getDatabasePlatform();

        return array_map(
            static fn (mixed $id): Ulid => $ulid->convertToPHPValue($id, $platform),
            $connection->fetchFirstColumn($sql, $params, $types),
        );
    }

    /**
     * Locks and returns the ids of the series with a first start already come.
     * Same lock as `lockDueIds`, taken on the series: two claims queue on it, and
     * the one that comes second finds the instance of the first.
     *
     * @return Ulid[]
     */
    public function lockDueSeriesIds(\DateTimeImmutable $now, ?User $user, int $limit): array
    {
        $connection = $this->getEntityManager()->getConnection();

        $sql = 'SELECT p.id FROM proaction p'
            .' JOIN event e ON e.id = p.id'
            .' JOIN agenda a ON a.id = e.agenda_id'
            .' WHERE e.rrule IS NOT NULL AND e.start_at <= :now AND e.status <> \'cancelled\''
            .(null !== $user ? ' AND a.user_id = :user' : '')
            .' ORDER BY e.start_at ASC, p.id ASC'
            .' LIMIT :limit'
            .' FOR UPDATE OF p SKIP LOCKED';

        $params = ['now' => $now, 'limit' => $limit];
        $types = ['now' => Types::DATETIMETZ_IMMUTABLE, 'limit' => ParameterType::INTEGER];
        if (null !== $user) {
            $params['user'] = $user->getId();
            $types['user'] = 'ulid';
        }

        $ulid = Type::getType('ulid');
        $platform = $connection->getDatabasePlatform();

        return array_map(
            static fn (mixed $id): Ulid => $ulid->convertToPHPValue($id, $platform),
            $connection->fetchFirstColumn($sql, $params, $types),
        );
    }
}
