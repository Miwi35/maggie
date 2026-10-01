<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Finance\Message\DeleteAccountCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DeleteAccount;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteAccountHandler
{
    public function __construct(
        private readonly DeleteAccount $deleteAccount,
        private readonly AccountRepository $accountRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    public function __invoke(DeleteAccountCommand $command): void
    {
        $account = $this->accountRepository->find($command->accountId)
            ?? throw new \DomainException("Account not found: {$command->accountId}");

        // The database cascade removes the account's transactions without any command of their own.
        $transactionIds = array_map(
            fn ($transaction) => (string) $transaction->getId(),
            $this->transactionRepository->findByAccount($account),
        );

        $this->deleteAccount->execute($account);

        foreach ($transactionIds as $transactionId) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: 'transactions', documentId: $transactionId));
        }
    }
}
