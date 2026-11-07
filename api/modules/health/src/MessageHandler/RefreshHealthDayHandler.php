<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Message\RefreshHealthDayCommand;
use Maggie\Health\Service\HealthDayRules;
use Maggie\Health\UseCase\RefreshHealthDay;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * A user gone in the meantime has no journal left to refresh. The refresh
 * runs after the write that raised it (a weighing, an intake, a sync), which
 * is stored already: a failing refresh is logged and never turns that write
 * into an error — the next change of the day, or the rebuild, lays it down.
 */
#[AsMessageHandler]
class RefreshHealthDayHandler
{
    public function __construct(
        private readonly RefreshHealthDay $refreshHealthDay,
        private readonly UserRepository $users,
        private readonly HealthDayRules $rules,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(RefreshHealthDayCommand $command): ?HealthDay
    {
        try {
            $user = $this->users->find($command->userId);
            if (null === $user) {
                return null;
            }

            return $this->refreshHealthDay->execute($user, $this->rules->date($command->date));
        } catch (\Throwable $e) {
            $this->logger->error('Health journal of {user} on {date} could not be refreshed: {error}', [
                'user' => $command->userId,
                'date' => $command->date,
                'error' => $e->getMessage(),
                'exception' => $e,
            ]);

            return null;
        }
    }
}
