<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteRecurringOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\UseCase\DeleteRecurringOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteRecurringOperationHandler
{
    public function __construct(
        private readonly DeleteRecurringOperation $deleteRecurringOperation,
        private readonly RecurringOperationRepository $operationRepository,
    ) {
    }

    public function __invoke(DeleteRecurringOperationCommand $command): void
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->recurringOperationId, 'user' => $command->userId])
            ?? throw new \DomainException("Recurring operation not found: {$command->recurringOperationId}");

        $this->deleteRecurringOperation->execute($operation);
    }
}
