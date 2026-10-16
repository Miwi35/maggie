<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\SettleRejectionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * « Régularisé »: the owner says a rejected payment was dealt with, so it
 * leaves the account's incidents and the pile stays meaningful (MAG-424).
 * Either leg of the rejection names it; both carry the date. Nothing is
 * reconciled automatically here: it is the owner's word, and it can be taken
 * back. Both legs change, so the projection publishes and reindexes both.
 */
#[AsMessageHandler]
class SettleRejectionHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @throws \DomainException when the line is not a rejected payment */
    public function __invoke(SettleRejectionCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId)
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        // Settling twice keeps the first date: the gesture is the same.
        $transaction->settleRejection(
            $command->settled ? ($transaction->getRejectionSettledAt() ?? new \DateTimeImmutable()) : null,
        );
        $this->em->flush();

        return $transaction;
    }
}
