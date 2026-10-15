<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Message\DeleteUtensilCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Utensil, void> */
class DeleteUtensilProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteUtensilCommand(
            utensilId: (string) $data->getId(),
        ));
    }
}
