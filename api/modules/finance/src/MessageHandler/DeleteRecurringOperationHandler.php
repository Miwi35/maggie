<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteRecurringOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DeleteRecurringOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteRecurringOperationHandler
{
    public function __construct(
        private readonly DeleteRecurringOperation $deleteRecurringOperation,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(DeleteRecurringOperationCommand $command): void
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->recurringOperationId, 'user' => $command->userId])
            ?? throw new \DomainException("Recurring operation not found: {$command->recurringOperationId}");

        // The database frees the lines on its own (SET NULL); doing it here
        // too lets the index and the open screens hear of it.
        foreach ($this->transactionRepository->findAttachedTo($operation) as $transaction) {
            $transaction->detachFromRecurring($transaction->getRecurringSource());
        }

        $this->deleteRecurringOperation->execute($operation);
    }
}
