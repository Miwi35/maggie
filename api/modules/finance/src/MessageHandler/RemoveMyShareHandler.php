<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\RemoveMyShareCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\RemoveMyShare;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RemoveMyShareHandler
{
    public function __construct(
        private readonly RemoveMyShare $removeMyShare,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(RemoveMyShareCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        return $this->removeMyShare->execute($transaction);
    }
}
