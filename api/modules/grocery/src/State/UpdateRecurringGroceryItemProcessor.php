<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Message\UpdateRecurringGroceryItemCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<RecurringGroceryItem, RecurringGroceryItem> */
class UpdateRecurringGroceryItemProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): RecurringGroceryItem
    {
        /** @var RecurringGroceryItem|null $previous */
        $previous = $context['previous_data'] ?? null;

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if (null !== $previous) {
            if (null === $data->getProduct() && null !== $previous->getProduct()) {
                $clearFields[] = 'productId';
            }
            if (null === $data->getCustomLabel() && null !== $previous->getCustomLabel()) {
                $clearFields[] = 'customLabel';
            }
            if (null === $data->getQuantity() && null !== $previous->getQuantity()) {
                $clearFields[] = 'quantity';
            }
            if (null === $data->getUnit() && null !== $previous->getUnit()) {
                $clearFields[] = 'unit';
            }
        }

        $envelope = $this->bus->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: (string) $data->getId(),
            frequency: $data->getFrequency()->value,
            productId: null !== $data->getProduct() ? (string) $data->getProduct()->getId() : null,
            customLabel: $data->getCustomLabel(),
            quantity: $data->getQuantity(),
            unit: $data->getUnit()?->value,
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
