<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Repository\HealthDayRepository;
use Maggie\Health\Service\HealthDayCalculator;

/**
 * Lays down a health journal day's computed columns from the detailed tables
 * (MAG-750). The typed values are never touched. A day left with neither a
 * source nor anything typed is removed, so that the journal holds exactly the
 * days a rebuild would give. Running it again changes nothing.
 */
class RefreshHealthDay
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly HealthDayRepository $days,
        private readonly HealthDayCalculator $calculator,
    ) {
    }

    public function execute(User $user, \DateTimeImmutable $date): ?HealthDay
    {
        $day = $this->days->findOneByDate($user, $date);
        $figures = $this->calculator->compute($user, $date);

        if (!$figures->hasSource) {
            if (null !== $day && $day->hasNothingTyped()) {
                $this->em->remove($day);
                $this->em->flush();

                return null;
            }
            if (null === $day) {
                return null;
            }
        }

        $day ??= (new HealthDay())->setUser($user)->setDate($date);
        $day->record($figures);
        $this->em->persist($day);
        $this->em->flush();

        return $day;
    }
}
