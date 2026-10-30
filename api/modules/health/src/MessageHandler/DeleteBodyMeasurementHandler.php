<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\UseCase\DeleteBodyMeasurement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteBodyMeasurementHandler
{
    public function __construct(
        private readonly DeleteBodyMeasurement $deleteBodyMeasurement,
        private readonly BodyMeasurementRepository $measurementRepository,
    ) {
    }

    public function __invoke(DeleteBodyMeasurementCommand $command): void
    {
        $measurement = $this->measurementRepository->findOneOfUser($command->userId, $command->measurementId)
            ?? throw new \DomainException("Measurement not found: {$command->measurementId}");

        $this->deleteBodyMeasurement->execute($measurement);
    }
}
