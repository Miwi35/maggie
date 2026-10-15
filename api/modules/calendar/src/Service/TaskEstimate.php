<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;

/**
 * The two fields that are Maggie's own on a task (MAG-497): how long it takes
 * and its first step. Checked here for every door that is not API Platform's
 * validator — the tools of Maggie, the bus.
 */
final class TaskEstimate
{
    public const FIRST_STEP_MAX_LENGTH = 255;

    /** @throws \DomainException */
    public static function check(?int $estimatedMinutes, ?string $firstStep): void
    {
        if (null !== $estimatedMinutes && ($estimatedMinutes < 1 || $estimatedMinutes > Task::MAX_ESTIMATED_MINUTES)) {
            throw new \DomainException(\sprintf('estimatedMinutes must be from 1 to %d minutes, not %d.', Task::MAX_ESTIMATED_MINUTES, $estimatedMinutes));
        }
        if (null !== $firstStep && mb_strlen($firstStep) > self::FIRST_STEP_MAX_LENGTH) {
            throw new \DomainException(\sprintf('firstStep must be at most %d characters.', self::FIRST_STEP_MAX_LENGTH));
        }
    }

    /** A blank first step is no first step. */
    public static function firstStep(?string $firstStep): ?string
    {
        $trimmed = null !== $firstStep ? trim($firstStep) : null;

        return '' === $trimmed ? null : $trimmed;
    }
}
