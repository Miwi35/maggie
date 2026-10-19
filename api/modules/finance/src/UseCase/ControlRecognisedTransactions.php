<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The owner checks lines an operation recognised: they are kept as checked
 * and do not come back to the next review. « Tout à conserver » also gives
 * « à conserver » to the spends among them that have no satisfaction yet,
 * never overriding one already given.
 *
 * Goes through the bus like any change of a transaction, so each line
 * publishes to Mercure and is reindexed.
 */
class ControlRecognisedTransactions
{
    public function __construct(
        private readonly OwnedReferenceResolver $references,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @param list<string> $transactionIds
     *
     * @return array{controlledCount: int, judgedCount: int}
     *
     * @throws \DomainException when a line is unknown, or was not recognised by an operation: nothing is written then
     */
    public function execute(User $user, array $transactionIds, bool $keepAll = false): array
    {
        $transactions = [];
        foreach (array_unique($transactionIds) as $id) {
            $transaction = $this->references->transaction($id, $user);
            if (!\in_array($transaction->getCategorySource(), [CategorySource::Rule, CategorySource::Series], true)) {
                throw new \InvalidArgumentException("Transaction {$id} was not recognised by an operation: only a recognised line can be checked.");
            }
            $transactions[] = $transaction;
        }

        $judged = 0;
        foreach ($transactions as $transaction) {
            $judges = $keepAll && $this->waitsForSatisfaction($transaction);
            $this->bus->dispatch(new UpdateTransactionCommand(
                userId: (string) $user->getId(),
                transactionId: (string) $transaction->getId(),
                retrospect: $judges ? RetrospectVerdict::Keep->value : null,
                controlled: true,
            ));
            $judged += $judges ? 1 : 0;
        }

        return ['controlledCount' => \count($transactions), 'judgedCount' => $judged];
    }

    private function waitsForSatisfaction(Transaction $transaction): bool
    {
        return $transaction->canBeJudged() && RetrospectVerdict::Unrated === $transaction->getRetrospect();
    }
}
