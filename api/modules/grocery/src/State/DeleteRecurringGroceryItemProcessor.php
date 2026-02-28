<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Message\DeleteRecurringGroceryItemCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<RecurringGroceryItem, void> */
class DeleteRecurringGroceryItemProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteRecurringGroceryItemCommand(
            recurringGroceryItemId: (string) $data->getId(),
        ));
    }
}
