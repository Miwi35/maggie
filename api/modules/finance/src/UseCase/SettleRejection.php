<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Entity\Transaction;

/**
 * « Régularisé »: the owner says a rejected payment was dealt with, so it
 * leaves the account's incidents and the pile stays meaningful (MAG-424).
 * Either leg of the rejection names it; both carry the date. Nothing is
 * reconciled automatically here: it is the owner's word, and it can be taken
 * back. No command returns the lines, so both are published and reindexed
 * here.
 */
class SettleRejection
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    /** @throws \DomainException when the line is not a rejected payment */
    public function settle(Transaction $transaction): Transaction
    {
        // Settling twice keeps the first date: the gesture is the same.
        $transaction->settleRejection($transaction->getRejectionSettledAt() ?? new \DateTimeImmutable());

        return $this->save($transaction);
    }

    /** @throws \DomainException when the line is not a rejected payment */
    public function unsettle(Transaction $transaction): Transaction
    {
        $transaction->settleRejection(null);

        return $this->save($transaction);
    }

    private function save(Transaction $transaction): Transaction
    {
        $this->em->flush();

        foreach ([$transaction, $transaction->getCounterpart()] as $line) {
            if (null !== $line) {
                $this->broadcaster->broadcast($line);
            }
        }

        return $transaction;
    }
}
