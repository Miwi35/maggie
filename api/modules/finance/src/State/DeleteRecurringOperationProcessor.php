<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\DeleteRecurringOperationCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<RecurringOperation, void> */
class DeleteRecurringOperationProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteRecurringOperationCommand(
            userId: (string) $data->getUser()->getId(),
            recurringOperationId: (string) $data->getId(),
        ));
    }
}
