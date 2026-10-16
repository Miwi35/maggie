<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\RelinkContactTransactionsCommand;
use Maggie\Finance\UseCase\RelinkContactTransactions;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RelinkContactTransactionsHandler
{
    public function __construct(
        private readonly RelinkContactTransactions $relinkContactTransactions,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(RelinkContactTransactionsCommand $command): int
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->relinkContactTransactions->execute($user);
    }
}
