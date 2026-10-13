<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ScheduleProactionHandler
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(ScheduleProactionCommand $command): Proaction
    {
        $user = $this->userRepository->find($command->userId) ?? throw new \DomainException('No user found.');

        return $this->proactions->schedule($user, $command->prompt, $command->scheduledAt, $command->summary);
    }
}
