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

        if (null !== $command->name) {
            $account->setName($command->name);
        }
        if (null !== $command->type) {
            $account->setType(AccountType::from($command->type));
        }
        if (null !== $command->bank) {
            $account->setBank($command->bank);
        } elseif ($command->clears('bank')) {
            $account->setBank(null);
        }
        if (null !== $command->currency) {
            $account->setCurrency($command->currency);
        }
        if (null !== $command->balanceCents) {
            $account->setBalanceCents($command->balanceCents);
        }
        if (null !== $command->isCushion) {
            $account->setIsCushion($command->isCushion);
        }
        if (null !== $command->externalAccountId) {
            $account->setExternalAccountId($command->externalAccountId);
        } elseif ($command->clears('externalAccountId')) {
            $account->setExternalAccountId(null);
        }

        return $this->updateAccount->execute($account);
    }
}
