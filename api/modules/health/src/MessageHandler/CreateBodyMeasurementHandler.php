<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Exception\DuplicateBodyMeasurementException;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Service\BodyMeasurementRules;
use Maggie\Health\UseCase\SaveBodyMeasurement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateBodyMeasurementHandler
{
    public function __construct(
        private readonly SaveBodyMeasurement $saveBodyMeasurement,
        private readonly UserRepository $userRepository,
        private readonly BodyMeasurementRepository $measurementRepository,
        private readonly BodyMeasurementRules $rules,
    ) {
    }

    public function __invoke(CreateBodyMeasurementCommand $command): BodyMeasurement
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $source = $this->rules->source($command->source);
        $measuredAt = $this->rules->measuredAt($command->measuredAt);
        $this->rules->assert($command->weightKg, $command->bodyFatPercent, $command->waistCm, $command->externalId);

        if (null !== $command->externalId && null !== $this->measurementRepository->findOneByExternalId($user, $command->externalId)) {
            throw new DuplicateBodyMeasurementException();
        }

        $measurement = (new BodyMeasurement())
            ->setUser($user)
            ->setMeasuredAt($measuredAt)
            ->setWeightKg($command->weightKg)
            ->setBodyFatPercent($command->bodyFatPercent)
            ->setWaistCm($command->waistCm)
            ->setSource($source)
            ->setExternalId($command->externalId)
            ->setExcluded($command->excluded);

        return $this->saveBodyMeasurement->execute($measurement);
    }
}
