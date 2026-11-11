<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Message\ApplySportEventChangeCommand;
use Maggie\Health\UseCase\ApplySportEventChange;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ApplySportEventChangeHandler
{
    public function __construct(
        private readonly ApplySportEventChange $applySportEventChange,
    ) {
    }

    /** @return Workout|null the session moved, null when nothing changed or it was deleted */
    public function __invoke(ApplySportEventChangeCommand $command): ?Workout
    {
        return $command->removed
            ? $this->applySportEventChange->removed($command->userId, $command->workoutId)
            : $this->applySportEventChange->moved($command->userId, $command->eventId);
    }
}
