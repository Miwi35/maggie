<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Files the uncategorized history under one rule — the lines it would have
 * filed itself, so the outcome is the one the whole catch-up pass would give:
 * a line a higher-priority rule claims is left to that rule.
 */
class ApplyCategorizationRule
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array{categorized: int, scanned: int} */
    public function execute(CategorizationRule $rule): array
    {
        $user = $rule->getUser();
        $transactions = $this->transactionRepository->findUncategorizedForUser($user);
        $categorized = 0;

        if ($rule->isActive()) {
            $rules = $this->ruleRepository->findActiveForUser($user);

            foreach ($transactions as $transaction) {
                $winner = $this->categorizeTransaction->winner($transaction, $rules);
                if (null === $winner || !$winner->getId()->equals($rule->getId())) {
                    continue;
                }

                // Go through the bus so each change publishes to Mercure and reindexes.
                $this->bus->dispatch(new UpdateTransactionCommand(
                    userId: (string) $user->getId(),
                    transactionId: (string) $transaction->getId(),
                    categoryId: (string) $rule->getCategory()->getId(),
                    categorySource: CategorySource::Rule->value,
                ));

                ++$categorized;
            }
        }

        return ['categorized' => $categorized, 'scanned' => \count($transactions)];
    }
}
