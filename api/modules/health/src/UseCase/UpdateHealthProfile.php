<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Service\HealthProfileReader;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Stores the profile and, when a weight is given, records it as a manual
 * weighing taken now (MAG-707 decision: one series of weights, spec part 16),
 * which Santé › Suivi lists like any other. The profile is returned with the
 * figures it reads after both.
 */
class UpdateHealthProfile
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly HealthProfileReader $reader,
        private readonly ClockInterface $clock,
    ) {
    }

    public function execute(HealthProfile $profile, ?float $weightKg): HealthProfile
    {
        $this->em->persist($profile);
        $this->em->flush();

        if (null !== $weightKg) {
            $this->bus->dispatch(new CreateBodyMeasurementCommand(
                userId: (string) $profile->getUser()->getId(),
                measuredAt: $this->clock->now()->format(\DATE_ATOM),
                weightKg: $weightKg,
            ));
        }

        return $this->reader->observe($profile);
    }
}
