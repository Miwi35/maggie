<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Message\DeleteRoutineCommand;
use Maggie\Calendar\Repository\RoutineCheckRepository;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * The ticks go with their routine — removed one by one rather than left to
 * the database's cascade, so the open screens hear of each.
 */
#[AsMessageHandler]
class DeleteRoutineHandler
{
    public function __construct(
        private readonly RoutineRepository $repository,
        private readonly RoutineCheckRepository $checks,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(DeleteRoutineCommand $command): void
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $routine = $this->repository->findOwned($user, $command->routineId)
            ?? throw new \DomainException("Routine not found: {$command->routineId}");

        foreach ($this->checks->findByRoutine($routine) as $check) {
            $this->em->remove($check);
        }
        $this->em->remove($routine);
        $this->em->flush();
    }
}
