<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\DetectSavingsAdvances;
use Maggie\Finance\UseCase\ReconcileAdvanceRepayments;
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
        private readonly DetectSavingsAdvances $detectSavingsAdvances,
        private readonly ReconcileAdvanceRepayments $reconcileAdvanceRepayments,
    ) {
    }

    /** Returns the line so the projection announces it; the other leg is announced with the flush. */
    public function __invoke(DetectInternalTransferCommand $command): ?Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId);
        if (null === $transaction) {
            return null;
        }

        $counterpart = $this->detectInternalTransfers->detectFor($transaction);
        if (null === $counterpart) {
            return $this->markLoneLeg($transaction);
        }

        // Taken from savings while the salary is late, it is an advance; the
        // exact amount of an open advance, back to its source, repays it.
        $repaid = $this->reconcileAdvanceRepayments->advanceRepaidBy($transaction, $counterpart);
        $kind = null !== $repaid ? TransferKind::Repayment : $this->detectSavingsAdvances->kindFor($transaction, $counterpart);
        $counterpart->pairWith($transaction, $kind, TransferSource::Auto);
        if (null !== $repaid) {
            ($transaction->getAmountCents() < 0 ? $transaction : $counterpart)->setRepays($repaid);
        }

        // Both legs settle no occurrence any more: the payment presented
        // again takes it, and the series recalibrates without them.
        $this->attachRecurring->releaseNeutral($counterpart);
        $this->attachRecurring->releaseNeutral($transaction);

        return $this->updateTransaction->execute($transaction);
    }

    /** The other leg is not there: the line itself says it is the owner's own movement. */
    private function markLoneLeg(Transaction $transaction): ?Transaction
    {
        if (null === $this->detectInternalTransfers->loneLegReason($transaction)) {
            return null;
        }

        $transaction->setTransferKind(TransferKind::Internal);
        $transaction->setTransferSource(TransferSource::Auto);
        $this->attachRecurring->releaseNeutral($transaction);

        return $this->updateTransaction->execute($transaction);
    }
}
