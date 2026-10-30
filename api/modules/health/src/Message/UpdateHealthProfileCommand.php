<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Changes the fields of the user's health profile it is given (MAG-707):
 * null leaves a field as it is. The values are checked by the handler. A
 * weight is not a profile field: it records a manual weighing.
 */
final readonly class UpdateHealthProfileCommand
{
    public function __construct(
        public string $userId,
        public mixed $sex = null,
        public mixed $birthYear = null,
        public mixed $heightCm = null,
        public mixed $weightKg = null,
        public mixed $activityLevel = null,
        public mixed $mode = null,
        public mixed $enteredNeedKcal = null,
        public mixed $stepGoal = null,
        public mixed $suggestionWindowStart = null,
        public mixed $suggestionWindowEnd = null,
        public mixed $sessionMinutes = null,
        public mixed $stepSource = null,
    ) {
    }
}
