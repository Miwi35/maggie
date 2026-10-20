<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Message\CreateRoutineCommand;
use Maggie\Calendar\Service\RoutineInput;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Refusals are {@see \DomainException}s, the caller's to read. */
#[AsMessageHandler]
class CreateRoutineHandler
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(CreateRoutineCommand $command): Routine
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $routine = (new Routine())->setUser($user)->setCreatedAt($this->clock->now());
        RoutineInput::apply($routine, $command->fields, creating: true);

        $this->em->persist($routine);
        $this->em->flush();

        return $routine;
    }
}
