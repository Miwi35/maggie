<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\DBAL\LockMode;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Message\CheckRoutineCommand;
use Maggie\Calendar\Repository\RoutineCheckRepository;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Ticks a routine on a day, or takes the tick back — each idempotent. A
 * day to come, or before the routine existed, is refused; a day it was not
 * due is not: done another day, it counts. No star, no bravo.
 */
#[AsMessageHandler]
class CheckRoutineHandler
{
    public function __construct(
        private readonly RoutineRepository $repository,
        private readonly RoutineCheckRepository $checks,
        private readonly UserRepository $userRepository,
        private readonly RoutineSchedule $schedule,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CheckRoutineCommand $command): ?RoutineCheck
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $routine = $this->repository->findOwned($user, $command->routineId)
            ?? throw new \DomainException("Routine not found: {$command->routineId}");

        if ($command->checked) {
            if ($command->date > $this->schedule->today($user)) {
                throw new \DomainException(\sprintf('date: %s is still to come, a routine is ticked once done.', $command->date->format('Y-m-d')));
            }
            if ($command->date < $this->schedule->createdOn($routine)) {
                throw new \DomainException(\sprintf('date: %s is before the routine was created.', $command->date->format('Y-m-d')));
            }
        }

        // The routine's row is locked for the read-then-write: a double tap
        // waits for the first tick instead of tripping the unique index.
        return $this->em->wrapInTransaction(function () use ($routine, $command): ?RoutineCheck {
            $this->em->lock($routine, LockMode::PESSIMISTIC_WRITE);
            $existing = $this->checks->findOneByRoutineAndDate($routine, $command->date);

            if (!$command->checked) {
                if (null !== $existing) {
                    $this->em->remove($existing);
                    $this->em->flush();
                }

                return null;
            }

            // Already ticked: nothing changes, nothing to announce.
            if (null !== $existing) {
                return null;
            }

            $check = new RoutineCheck($routine, $command->date);
            $this->em->persist($check);
            $this->em->flush();

            return $check;
        });
    }
}
