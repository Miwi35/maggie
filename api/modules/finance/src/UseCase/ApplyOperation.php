<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Gives the history to one operation — the lines it would have taken itself,
 * so the outcome is the one the whole catch-up pass would give.
 *
 * A series attaches the occurrences it recognises (the catch-up pass of the
 * series, which never steals a line another series settles). An operation
 * without recurrence files the uncategorized lines it would have filed: a line
 * a higher-priority one claims is left to that one.
 */
class ApplyOperation
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly AttachRecurringTransactions $attachRecurring,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array{categorized: int, scanned: int} */
    public function execute(RecurringOperation $operation): array
    {
        $user = $operation->getUser();

        if ($operation->hasRecurrence()) {
            $pass = $this->attachRecurring->execute($user);

            return ['categorized' => $pass['attached'], 'scanned' => $pass['scanned']];
        }

        $transactions = $this->transactionRepository->findUncategorizedForUser($user);
        $categorized = 0;

        if ($operation->isActive()) {
            $operations = $this->operationRepository->findActiveWithoutRecurrenceForUser($user);
            foreach ($transactions as $transaction) {
                $winner = $this->categorizeTransaction->winner($transaction, $operations);
                if (null === $winner || !$winner->getId()->equals($operation->getId())) {
                    continue;
                }

                // Go through the bus so each change publishes to Mercure and reindexes.
                $this->bus->dispatch(new UpdateTransactionCommand(
                    userId: (string) $user->getId(),
                    transactionId: (string) $transaction->getId(),
                    qualifiedByRuleId: (string) $operation->getId(),
                ));
                ++$categorized;
            }
        }

        return ['categorized' => $categorized, 'scanned' => \count($transactions)];
    }
}
