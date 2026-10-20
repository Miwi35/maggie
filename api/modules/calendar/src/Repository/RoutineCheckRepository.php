<?php

declare(strict_types=1);

namespace Maggie\Calendar\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\DBAL\Types\Types;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;

/** @extends ServiceEntityRepository<RoutineCheck> */
class RoutineCheckRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, RoutineCheck::class);
    }

    public function findOneByRoutineAndDate(Routine $routine, \DateTimeImmutable $date): ?RoutineCheck
    {
        return $this->createQueryBuilder('c')
            ->where('c.routine = :routine')
            ->andWhere('c.date = :date')
            ->setParameter('routine', $routine->getId(), 'ulid')
            ->setParameter('date', $date, Types::DATE_IMMUTABLE)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /** @return list<RoutineCheck> */
    public function findByRoutine(Routine $routine): array
    {
        return $this->findByRoutines([$routine])[(string) $routine->getId()] ?? [];
    }

    /**
     * The ticks of these routines, in one query.
     *
     * @param list<Routine> $routines
     *
     * @return array<string, list<RoutineCheck>> by routine id
     */
    public function findByRoutines(array $routines): array
    {
        if ([] === $routines) {
            return [];
        }

        /** @var list<RoutineCheck> $checks */
        $checks = $this->createQueryBuilder('c')
            ->where('c.routine IN (:routines)')
            ->setParameter('routines', array_map(static fn (Routine $routine): string => $routine->getId()->toRfc4122(), $routines), ArrayParameterType::STRING)
            ->getQuery()
            ->getResult();

        $byRoutine = [];
        foreach ($checks as $check) {
            $byRoutine[(string) $check->getRoutine()->getId()][] = $check;
        }

        return $byRoutine;
    }
}
