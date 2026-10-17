<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DeleteOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteOperationHandler
{
    public function __construct(
        private readonly DeleteOperation $deleteOperation,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(DeleteOperationCommand $command): void
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->operationId, 'user' => $command->userId])
            ?? throw new \DomainException("Operation not found: {$command->operationId}");

        // The database frees the lines on its own (SET NULL); doing it here
        // too lets the index and the open screens hear of it.
        foreach ($this->transactionRepository->findAttachedTo($operation) as $transaction) {
            $transaction->detachFromRecurring($transaction->getRecurringSource());
        }

        $this->deleteOperation->execute($operation);
    }
}
