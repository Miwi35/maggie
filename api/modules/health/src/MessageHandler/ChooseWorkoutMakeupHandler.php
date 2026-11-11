<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Exception\InvalidWorkoutMakeupException;
use Maggie\Health\Exception\WorkoutMakeupNotFoundException;
use Maggie\Health\Message\ChooseWorkoutMakeupCommand;
use Maggie\Health\Repository\WorkoutMakeupRepository;
use Maggie\Health\UseCase\ChooseWorkoutMakeup;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ChooseWorkoutMakeupHandler
{
    public function __construct(
        private readonly WorkoutMakeupRepository $makeups,
        private readonly ChooseWorkoutMakeup $chooseWorkoutMakeup,
    ) {
    }

    public function __invoke(ChooseWorkoutMakeupCommand $command): WorkoutMakeup
    {
        $option = MakeupOption::tryFrom($command->option) ?? throw new InvalidWorkoutMakeupException();
        $makeup = $this->makeups->findOneOfUser($command->userId, $command->makeupId)
            ?? throw new WorkoutMakeupNotFoundException($command->makeupId);

        return $this->chooseWorkoutMakeup->execute($makeup, $option);
    }
}
