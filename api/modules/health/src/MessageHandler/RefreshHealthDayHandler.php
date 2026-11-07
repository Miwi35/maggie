<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Message\RefreshHealthDayCommand;
use Maggie\Health\Service\HealthDayRules;
use Maggie\Health\UseCase\RefreshHealthDay;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** A user gone in the meantime has no journal left to refresh. */
#[AsMessageHandler]
class RefreshHealthDayHandler
{
    public function __construct(
        private readonly RefreshHealthDay $refreshHealthDay,
        private readonly UserRepository $users,
        private readonly HealthDayRules $rules,
    ) {
    }

    public function __invoke(RefreshHealthDayCommand $command): ?HealthDay
    {
        $user = $this->users->find($command->userId);
        if (null === $user) {
            return null;
        }

        return $this->refreshHealthDay->execute($user, $this->rules->date($command->date));
    }
}
