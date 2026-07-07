<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteAccountCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\DeleteAccount;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteAccountHandler
{
    public function __construct(
        private readonly DeleteAccount $deleteAccount,
        private readonly AccountRepository $accountRepository,
    ) {
    }

    public function __invoke(DeleteAccountCommand $command): void
    {
        $account = $this->accountRepository->find($command->accountId)
            ?? throw new \DomainException("Account not found: {$command->accountId}");

        $this->deleteAccount->execute($account);
    }
}
