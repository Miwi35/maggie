<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Message\AllocateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\AllocateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class AllocateTransactionHandler
{
    public function __construct(
        private readonly AllocateTransaction $allocateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(AllocateTransactionCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");
        $kind = PeerKind::tryFrom($command->kind)
            ?? throw new \DomainException(sprintf('kind must be one of: %s.', implode(', ', array_map(static fn (PeerKind $kind): string => $kind->value, PeerKind::cases()))));

        return $this->allocateTransaction->execute($transaction, $kind, $command->allocations, $command->debt, $command->claim);
    }
}
