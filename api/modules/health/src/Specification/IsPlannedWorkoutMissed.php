<?php

declare(strict_types=1);

namespace Maggie\Health\Specification;

use Maggie\Health\Entity\Workout;

/**
 * « Séance manquée » (MAG-771): a planned session, not a make-up, that nothing
 * kept, {@see self::GRACE_MINUTES} after its end. A date that arrives, so no
 * changeset: `MissedWorkoutReminderSource` asks it each minute, the use case
 * asks it again. Whether a session done keeps it is the caller's to say.
 */
final readonly class IsPlannedWorkoutMissed
{
    public const GRACE_MINUTES = 30;

    public function __construct(
        private \DateTimeImmutable $now,
        private bool $kept,
    ) {
    }

    public function isSatisfiedBy(Workout $workout): bool
    {
        return $workout->isPlanned()
            && null === $workout->getMakeupOf()
            && !$this->kept
            && self::isOver($workout, $this->now);
    }

    /** Over, grace included: from then on, not kept means missed. */
    public static function isOver(Workout $workout, \DateTimeImmutable $now): bool
    {
        return $now >= $workout->endsAt()->modify(\sprintf('+%d minutes', self::GRACE_MINUTES));
    }
}
