<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\UpdateGroceryListCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<GroceryList, GroceryList> */
class UpdateGroceryListProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): GroceryList
    {
        $envelope = $this->bus->dispatch(new UpdateGroceryListCommand(
            groceryListId: (string) $data->getId(),
            status: $data->getStatus()->value,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
