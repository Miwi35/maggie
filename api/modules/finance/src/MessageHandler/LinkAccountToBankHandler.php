<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\LinkAccountToBankCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\LinkAccountToBank;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class LinkAccountToBankHandler
{
    public function __construct(
        private readonly LinkAccountToBank $linkAccountToBank,
        private readonly AccountRepository $accountRepository,
    ) {
    }

    /** @return array{kept: string, moved: int, dropped: int, linked: bool} */
    public function __invoke(LinkAccountToBankCommand $command): array
    {
        $kept = $this->accountRepository->findOneBy(['id' => $command->accountId, 'user' => $command->userId])
            ?? throw new \DomainException("Account not found: {$command->accountId}");
        $bankAccount = $this->accountRepository->findOneBy(['id' => $command->bankAccountId, 'user' => $command->userId])
            ?? throw new \DomainException("Account not found: {$command->bankAccountId}");

        return $this->linkAccountToBank->execute($kept, $bankAccount, $command->confirm);
    }
}
