<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\ApplyOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\UseCase\ApplyOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ApplyOperationHandler
{
    public function __construct(
        private readonly RecurringOperationRepository $operationRepository,
        private readonly ApplyOperation $applyOperation,
    ) {
    }

    /** @return array{categorized: int, scanned: int} */
    public function __invoke(ApplyOperationCommand $command): array
    {
        $operation = $this->operationRepository->find($command->operationId)
            ?? throw new \DomainException("Operation not found: {$command->operationId}");

        return $this->applyOperation->execute($operation);
    }
}
