<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Health\Message\FilePlannedWorkoutCommand;
use Maggie\Health\UseCase\FilePlannedWorkoutInAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class FilePlannedWorkoutHandler
{
    public function __construct(
        private readonly FilePlannedWorkoutInAgenda $filePlannedWorkout,
    ) {
    }

    /** @return Event|null the session's event, null when it has none (any more) */
    public function __invoke(FilePlannedWorkoutCommand $command): ?Event
    {
        return $this->filePlannedWorkout->execute($command->userId, $command->workoutId, $command->eventId);
    }
}
