<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\RemoveDebtCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\RemoveDebt;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RemoveDebtHandler
{
    public function __construct(
        private readonly RemoveDebt $removeDebt,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(RemoveDebtCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        return $this->removeDebt->execute($transaction);
    }
}
