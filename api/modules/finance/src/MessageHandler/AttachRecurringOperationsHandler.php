<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\AttachRecurringOperationsCommand;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class AttachRecurringOperationsHandler
{
    public function __construct(
        private readonly AttachRecurringTransactions $attachRecurring,
        private readonly UserRepository $userRepository,
    ) {
    }

    /**
     * @return array{dryRun: bool, scanned: int, attached: int, proposed: int, attachments: list<array<string, mixed>>, proposals: list<array<string, mixed>>}
     */
    public function __invoke(AttachRecurringOperationsCommand $command): array
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->attachRecurring->execute($user, $command->limitDays, $command->dryRun);
    }
}
