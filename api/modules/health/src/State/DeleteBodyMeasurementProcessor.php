<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<BodyMeasurement, void> */
class DeleteBodyMeasurementProcessor implements ProcessorInterface
{
    use DispatchesBodyMeasurementCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->dispatchBodyMeasurementCommand($this->bus, new DeleteBodyMeasurementCommand(
            userId: (string) $data->getUser()->getId(),
            measurementId: (string) $data->getId(),
        ));
    }
}
