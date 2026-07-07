<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Account;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Message\UpdateAccountCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\UpdateAccount;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateAccountHandler
{
    public function __construct(
        private readonly UpdateAccount $updateAccount,
        private readonly AccountRepository $accountRepository,
    ) {
    }

    public function __invoke(UpdateAccountCommand $command): Account
    {
        $account = $this->accountRepository->find($command->accountId)
            ?? throw new \DomainException("Account not found: {$command->accountId}");

        if ($command->name !== null) {
            $account->setName($command->name);
        }
        if ($command->type !== null) {
            $account->setType(AccountType::from($command->type));
        }
        if ($command->bank !== null) {
            $account->setBank($command->bank);
        }
        if ($command->currency !== null) {
            $account->setCurrency($command->currency);
        }
        if ($command->balanceCents !== null) {
            $account->setBalanceCents($command->balanceCents);
        }
        if ($command->isCushion !== null) {
            $account->setIsCushion($command->isCushion);
        }
        if ($command->bridgeAccountId !== null) {
            $account->setBridgeAccountId($command->bridgeAccountId);
        }

        return $this->updateAccount->execute($account);
    }
}
