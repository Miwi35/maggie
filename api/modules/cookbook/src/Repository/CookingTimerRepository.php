<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<CookingTimer> */
class CookingTimerRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, CookingTimer::class);
    }

    /**
     * The user's timers in these statuses, the first started first.
     *
     * @param list<CookingTimerStatus> $statuses
     *
     * @return list<CookingTimer>
     */
    public function findByUserAndStatuses(User $user, array $statuses): array
    {
        if ([] === $statuses) {
            return [];
        }

        /** @var list<CookingTimer> $timers */
        $timers = $this->createQueryBuilder('t')
            ->where('t.user = :user')
            ->andWhere('t.status IN (:statuses)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('statuses', array_map(static fn (CookingTimerStatus $s) => $s->value, $statuses))
            ->orderBy('t.createdAt', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();

        return $timers;
    }

    public function findOneForUser(string $id, User $user): ?CookingTimer
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }
}
