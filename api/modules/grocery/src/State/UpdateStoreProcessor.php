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
        $envelope = $this->bus->dispatch(new UpdateStoreCommand(
            storeId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            visitOrder: $data->getVisitOrder(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
