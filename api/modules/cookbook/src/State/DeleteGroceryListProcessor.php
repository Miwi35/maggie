<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\DeleteGroceryListCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<GroceryList, void> */
class DeleteGroceryListProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteGroceryListCommand(
            groceryListId: (string) $data->getId(),
        ));
    }
}
