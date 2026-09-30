<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Catch-up pass: runs the rules over the transactions that are still
 * uncategorized, so a rule created today also files yesterday's history.
 */
class ApplyCategorizationRules
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array{categorized: int, scanned: int} */
    public function execute(User $user): array
    {
        $transactions = $this->transactionRepository->findUncategorizedForUser($user);
        $categorized = 0;

        foreach ($transactions as $transaction) {
            $rule = $this->categorizeTransaction->match($transaction);
            if (null === $rule) {
                continue;
            }

            // Go through the bus so each change publishes to Mercure and reindexes.
            $this->bus->dispatch(new UpdateTransactionCommand(
                transactionId: (string) $transaction->getId(),
                categoryId: (string) $rule->getCategory()->getId(),
                categorySource: CategorySource::Rule->value,
            ));

            ++$categorized;
        }

        return ['categorized' => $categorized, 'scanned' => \count($transactions)];
    }
}
