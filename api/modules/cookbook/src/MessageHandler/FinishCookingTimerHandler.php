<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Message\FinishCookingTimerCommand;
use Maggie\Cookbook\Service\CookingTimers;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Handled by the worker once the delay of the message is over (MAG-582). */
#[AsMessageHandler]
class FinishCookingTimerHandler
{
    public function __construct(
        private readonly CookingTimers $timers,
    ) {
    }

    public function __invoke(FinishCookingTimerCommand $command): ?CookingTimer
    {
        return $this->timers->finish($command->timerId, $command->revision);
    }
}
