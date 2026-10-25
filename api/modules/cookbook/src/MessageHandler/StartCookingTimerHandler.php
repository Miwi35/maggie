<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Message\StartCookingTimerCommand;
use Maggie\Cookbook\Service\CookingTimers;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class StartCookingTimerHandler
{
    public function __construct(
        private readonly CookingTimers $timers,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(StartCookingTimerCommand $command): CookingTimer
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->timers->start($user, $command->label, $command->recipeId, $command->stepPosition, $command->durationSeconds);
    }
}
