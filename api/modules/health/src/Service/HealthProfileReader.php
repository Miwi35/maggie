<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Repository\HealthProfileRepository;
use Psr\Clock\ClockInterface;

/**
 * The profile as it is read (MAG-707): the stored one, or the defaults of a
 * user who never wrote one — not saved, the row is laid down on first write —
 * given the latest weighing and the current year.
 */
final class HealthProfileReader
{
    public function __construct(
        private readonly HealthProfileRepository $profiles,
        private readonly BodyMeasurementRepository $measurements,
        private readonly ClockInterface $clock,
    ) {
    }

    public function forUser(User $user): HealthProfile
    {
        return $this->observe($this->profiles->findOneByUser($user) ?? (new HealthProfile())->setUser($user));
    }

    public function observe(HealthProfile $profile): HealthProfile
    {
        return $profile->observe(
            $this->measurements->latestWeighing($profile->getUser())?->getWeightKg(),
            (int) $this->clock->now()->format('Y'),
        );
    }

    /**
     * What the controller and the tools return: the profile with its id, null
     * while it has never been written.
     *
     * @return array<string, mixed>
     */
    public function describe(User $user): array
    {
        $stored = $this->profiles->findOneByUser($user);
        $profile = $this->observe($stored ?? (new HealthProfile())->setUser($user));

        return ['id' => null === $stored ? null : (string) $stored->getId()] + $profile->describe();
    }
}
