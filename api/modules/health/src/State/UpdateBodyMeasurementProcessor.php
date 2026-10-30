<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and an optional one that is null is cleared.
 * `{"excluded": true}` is « Écarter ».
 *
 * @implements ProcessorInterface<BodyMeasurement, BodyMeasurement>
 */
class UpdateBodyMeasurementProcessor implements ProcessorInterface
{
    use DispatchesBodyMeasurementCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): BodyMeasurement
    {
        return $this->dispatchBodyMeasurementCommand($this->bus, new UpdateBodyMeasurementCommand(
            userId: (string) $data->getUser()->getId(),
            measurementId: (string) $data->getId(),
            measuredAt: $data->getMeasuredAt()->format(\DATE_ATOM),
            weightKg: $data->getWeightKg(),
            bodyFatPercent: $data->getBodyFatPercent(),
            waistCm: $data->getWaistCm(),
            source: $data->getSource()->value,
            externalId: $data->getExternalId(),
            excluded: $data->isExcluded(),
            clearWeight: null === $data->getWeightKg(),
            clearBodyFat: null === $data->getBodyFatPercent(),
            clearWaist: null === $data->getWaistCm(),
            clearExternalId: null === $data->getExternalId(),
        ))->last(HandledStamp::class)->getResult();
    }
}
