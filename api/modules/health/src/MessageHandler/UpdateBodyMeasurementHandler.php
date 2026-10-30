<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Exception\DuplicateBodyMeasurementException;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Service\BodyMeasurementRules;
use Maggie\Health\UseCase\SaveBodyMeasurement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the measurement as it was.
 */
#[AsMessageHandler]
class UpdateBodyMeasurementHandler
{
    public function __construct(
        private readonly SaveBodyMeasurement $saveBodyMeasurement,
        private readonly BodyMeasurementRepository $measurementRepository,
        private readonly BodyMeasurementRules $rules,
    ) {
    }

    public function __invoke(UpdateBodyMeasurementCommand $command): BodyMeasurement
    {
        $measurement = $this->measurementRepository->findOneOfUser($command->userId, $command->measurementId)
            ?? throw new \DomainException("Measurement not found: {$command->measurementId}");

        $source = null === $command->source ? $measurement->getSource() : $this->rules->source($command->source);
        $measuredAt = null === $command->measuredAt ? $measurement->getMeasuredAt() : $this->rules->measuredAt($command->measuredAt);
        $weight = $command->clearWeight ? null : ($command->weightKg ?? $measurement->getWeightKg());
        $bodyFat = $command->clearBodyFat ? null : ($command->bodyFatPercent ?? $measurement->getBodyFatPercent());
        $waist = $command->clearWaist ? null : ($command->waistCm ?? $measurement->getWaistCm());
        $externalId = $command->clearExternalId ? null : ($command->externalId ?? $measurement->getExternalId());
        $excluded = $command->excluded ?? $measurement->isExcluded();
        $this->rules->assert($weight, $bodyFat, $waist, $externalId);

        if (null !== $externalId && $externalId !== $measurement->getExternalId()) {
            $other = $this->measurementRepository->findOneByExternalId($command->userId, $externalId);
            if (null !== $other && !$other->getId()->equals($measurement->getId())) {
                throw new DuplicateBodyMeasurementException();
            }
        }

        $measurement
            ->setMeasuredAt($measuredAt)
            ->setWeightKg($weight)
            ->setBodyFatPercent($bodyFat)
            ->setWaistCm($waist)
            ->setSource($source)
            ->setExternalId($externalId)
            ->setExcluded($excluded);

        return $this->saveBodyMeasurement->execute($measurement);
    }
}
