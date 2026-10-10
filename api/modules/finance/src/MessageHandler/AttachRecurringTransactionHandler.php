<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\AttachRecurringTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * The occurrence of a series a line settles, once a transfer, a rejection and
 * the rules have had their say: a neutral movement is never one, and the
 * series' category replaces the rule's — a category typed by hand stays.
 */
#[AsMessageHandler]
class AttachRecurringTransactionHandler
{
    public function __construct(
        private readonly AttachRecurringTransactions $attachRecurring,
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** Returns the line so the projection announces it; a recalibrated series is announced with the flush. */
    public function __invoke(AttachRecurringTransactionCommand $command): ?Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId);
        if (null === $transaction || null !== $transaction->getRecurringOperation()) {
            return null;
        }

        $match = $this->attachRecurring->attachFor($transaction);
        if (null === $match || !$match->attached) {
            return null;
        }

        return $this->updateTransaction->execute($transaction);
    }
}
