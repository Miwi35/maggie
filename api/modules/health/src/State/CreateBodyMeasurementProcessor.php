<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<BodyMeasurement, BodyMeasurement> */
class CreateBodyMeasurementProcessor implements ProcessorInterface
{
    use DispatchesBodyMeasurementCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): BodyMeasurement
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchBodyMeasurementCommand($this->bus, new CreateBodyMeasurementCommand(
            userId: (string) $user->getId(),
            measuredAt: $data->getMeasuredAt()->format(\DATE_ATOM),
            weightKg: $data->getWeightKg(),
            bodyFatPercent: $data->getBodyFatPercent(),
            waistCm: $data->getWaistCm(),
            source: $data->getSource()->value,
            externalId: $data->getExternalId(),
            excluded: $data->isExcluded(),
        ))->last(HandledStamp::class)->getResult();
    }
}
