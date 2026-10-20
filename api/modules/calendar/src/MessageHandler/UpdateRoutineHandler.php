<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Message\UpdateRoutineCommand;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Service\RoutineInput;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Refusals are {@see \DomainException}s, the caller's to read; a refused change changes nothing. */
#[AsMessageHandler]
class UpdateRoutineHandler
{
    public function __construct(
        private readonly RoutineRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(UpdateRoutineCommand $command): Routine
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $routine = $this->repository->findOwned($user, $command->routineId)
            ?? throw new \DomainException("Routine not found: {$command->routineId}");

        // Applied to a copy first: a field refused after another was set leaves the routine as it was.
        RoutineInput::apply(clone $routine, $command->fields);
        RoutineInput::apply($routine, $command->fields);

        $this->em->flush();

        return $routine;
    }
}
