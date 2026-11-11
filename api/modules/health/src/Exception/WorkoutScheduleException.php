<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/**
 * A planned strength session the week cannot take (MAG-776). `current` lists the
 * strength sessions of that week, for the caller to choose another slot.
 */
final class WorkoutScheduleException extends InvalidWorkoutException
{
    /** @param list<array{id: string, title: string, startedAt: string, source: string, eccentric: bool}> $current */
    public function __construct(string $field, string $message, public readonly array $current)
    {
        parent::__construct($field, $message);
    }
}
