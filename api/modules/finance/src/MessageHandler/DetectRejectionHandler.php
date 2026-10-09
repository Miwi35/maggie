<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\DetectRejectionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DetectRejections;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** A credit that gives back a payment cancels it: neither line is an expense or an income. */
#[AsMessageHandler]
class DetectRejectionHandler
{
    public function __construct(
        private readonly DetectRejections $detectRejections,
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(DetectRejectionCommand $command): ?Transaction
    {
        $credit = $this->transactionRepository->find($command->transactionId);
        $debit = null === $credit ? null : $this->detectRejections->detectFor($credit);
        if (null === $credit || null === $debit) {
            return null;
        }

        $debit->markAsRejection($credit, TransferSource::Auto);
        $this->updateTransaction->execute($credit);
        $this->detectRejections->notify($debit, $credit);

        return $credit;
    }
}
