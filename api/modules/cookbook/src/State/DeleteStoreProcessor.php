<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Store;
use Maggie\Cookbook\Message\DeleteStoreCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Store, void> */
class DeleteStoreProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteStoreCommand(
            storeId: (string) $data->getId(),
        ));
    }
}
