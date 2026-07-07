<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Message\CreateAccountCommand;
use Maggie\Finance\UseCase\CreateAccount;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateAccountHandler
{
    public function __construct(
        private readonly CreateAccount $createAccount,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateAccountCommand $command): Account
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $account = new Account();
        $account->setUser($user);
        $account->setName($command->name);
        $account->setType(AccountType::from($command->type));
        $account->setBank($command->bank);
        $account->setCurrency($command->currency);
        $account->setBalanceCents($command->balanceCents);
        $account->setIsCushion($command->isCushion);
        $account->setBridgeAccountId($command->bridgeAccountId);

        return $this->createAccount->execute($account);
    }
}
