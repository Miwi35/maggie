<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Exception\InvalidHealthDayException;

/** What a health journal day accepts as typed values (MAG-750), for every door. */
final class HealthDayRules
{
    /** @throws InvalidHealthDayException */
    public function date(string $date): \DateTimeImmutable
    {
        try {
            return DailyEnergyReader::day('date', $date);
        } catch (\InvalidArgumentException $e) {
            throw new InvalidHealthDayException('date', 'must be a date YYYY-MM-DD.');
        }
    }

    /** Mood or fatigue: a whole number from 1 to 10. */
    public function score(mixed $value, string $field): int
    {
        if (!\is_int($value) || $value < HealthDay::MIN_SCORE || $value > HealthDay::MAX_SCORE) {
            throw new InvalidHealthDayException($field, 'must be a whole number from '.HealthDay::MIN_SCORE.' to '.HealthDay::MAX_SCORE.'.');
        }

        return $value;
    }

    /** The note, trimmed; a blank note is no note. */
    public function note(mixed $value): ?string
    {
        if (!\is_string($value)) {
            throw new InvalidHealthDayException('note', 'must be a text.');
        }
        $note = trim($value);
        if (mb_strlen($note) > HealthDay::MAX_NOTE_LENGTH) {
            throw new InvalidHealthDayException('note', 'is at most '.HealthDay::MAX_NOTE_LENGTH.' characters.');
        }

        return '' === $note ? null : $note;
    }
}
