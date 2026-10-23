<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Message\DeletePlacementCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Placement, void> */
class DeletePlacementProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->dispatchProjectCommand($this->bus, new DeletePlacementCommand(
            userId: (string) $data->getUser()->getId(),
            placementId: (string) $data->getId(),
        ));
    }
}
