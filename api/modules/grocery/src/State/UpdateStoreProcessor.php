<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\UpdateStoreCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Store, Store> */
class UpdateStoreProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Store
    {
        /** @var Store|null $previous */
        $previous = $context['previous_data'] ?? null;

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if (null !== $previous && null === $data->getDescription() && null !== $previous->getDescription()) {
            $clearFields[] = 'description';
        }

        $envelope = $this->bus->dispatch(new UpdateStoreCommand(
            storeId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            visitOrder: $data->getVisitOrder(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
