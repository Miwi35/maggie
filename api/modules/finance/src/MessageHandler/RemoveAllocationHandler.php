<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\RemoveAllocationCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\AllocateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RemoveAllocationHandler
{
    public function __construct(
        private readonly AllocateTransaction $allocateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(RemoveAllocationCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        return $this->allocateTransaction->remove($transaction);
    }
}
