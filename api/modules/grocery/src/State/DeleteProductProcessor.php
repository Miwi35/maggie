<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\DeleteProductCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Product, void> */
class DeleteProductProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteProductCommand(
            productId: (string) $data->getId(),
        ));
    }
}
