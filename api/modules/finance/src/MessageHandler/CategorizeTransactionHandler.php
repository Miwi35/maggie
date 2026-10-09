<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Message\CategorizeTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\CategorizeTransaction;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Files a transaction under the category of the first rule that claims it,
 * unless it is a transfer or a rejection: those are no expense nor income.
 */
#[AsMessageHandler]
class CategorizeTransactionHandler
{
    public function __construct(
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(CategorizeTransactionCommand $command): ?Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId);
        if (null === $transaction || TransferKind::None !== $transaction->getTransferKind()) {
            return null;
        }

        if (!$this->categorizeTransaction->apply($transaction)) {
            return null;
        }

        return $this->updateTransaction->execute($transaction);
    }
}
