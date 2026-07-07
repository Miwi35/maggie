<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DeleteTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteTransactionHandler
{
    public function __construct(
        private readonly DeleteTransaction $deleteTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(DeleteTransactionCommand $command): void
    {
        $transaction = $this->transactionRepository->find($command->transactionId)
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        $this->deleteTransaction->execute($transaction);
    }
}
