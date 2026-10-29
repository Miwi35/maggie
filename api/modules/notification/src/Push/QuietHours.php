<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Psr\Clock\ClockInterface;

/**
 * Is it quiet for this user right now (MAG-687)? The window is the user's own
 * (`quietHoursStart`..`quietHoursEnd`, "HH:MM" in their timezone) and may wrap
 * midnight; start included, end excluded. Unset, or start = end: never quiet.
 */
class QuietHours
{
    public function __construct(
        private readonly UserPreferenceRepository $preferences,
        private readonly ClockInterface $clock,
    ) {
    }

    public function isActive(User $user): bool
    {
        $preference = $this->preferences->findOneByUser($user);
        $start = $preference?->getQuietHoursStart();
        $end = $preference?->getQuietHoursEnd();
        if (null === $preference || null === $start || null === $end) {
            return false;
        }

        try {
            $now = $this->clock->now()->setTimezone(new \DateTimeZone($preference->getTimezone()))->format('H:i');
        } catch (\Exception) {
            return false;
        }

        if ($start === $end) {
            return false;
        }

        // "HH:MM" strings compare in time order.
        return $start < $end ? ($now >= $start && $now < $end) : ($now >= $start || $now < $end);
    }
}
