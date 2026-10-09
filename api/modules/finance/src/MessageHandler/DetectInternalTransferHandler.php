<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** A movement between two of the owner's own accounts is neither an expense nor an income. */
#[AsMessageHandler]
class DetectInternalTransferHandler
{
    public function __construct(
        private readonly DetectInternalTransfers $detectInternalTransfers,
        private readonly UpdateTransaction $updateTransaction,
        private readonly AttachRecurringTransactions $attachRecurring,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** Returns the line so the projection announces it; the other leg is announced with the flush. */
    public function __invoke(DetectInternalTransferCommand $command): ?Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId);
        $counterpart = null === $transaction ? null : $this->detectInternalTransfers->detectFor($transaction);
        if (null === $transaction || null === $counterpart) {
            return null;
        }

        $counterpart->markAsInternalTransfer($transaction, TransferSource::Auto);

        // Both legs settle no occurrence any more: the payment presented
        // again takes it, and the series recalibrates without them.
        $this->attachRecurring->releaseNeutral($counterpart);
        $this->attachRecurring->releaseNeutral($transaction);

        return $this->updateTransaction->execute($transaction);
    }
}
