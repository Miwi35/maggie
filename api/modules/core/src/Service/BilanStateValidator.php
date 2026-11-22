<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

/**
 * The state of the « Bilan du soir » activity (MAG-747): `step`, `answers`
 * {tasks[{taskId, choice}], mood, fatigue, note} and `instructions`
 * [{id, text, kind, targetId}], plus `tomorrowTasks` and `tomorrowSpares`
 * [{taskId, title, reason?}]. A state without `step` is a prepared bilan,
 * not yet started. Only what is present is checked; the
 * activity's other states stay opaque to the API.
 */
final class BilanStateValidator
{
    public const STEPS = ['journee', 'finance', 'sante', 'consignes', 'demain'];
    public const CHOICES = ['done', 'tomorrow', 'later'];
    public const KINDS = ['reminder', 'task', 'instruction'];
    public const REASON_MAX = 80;

    /** @return string|null the first problem found, null when the state is valid */
    public static function validate(string $originId, \stdClass $state): ?string
    {
        $day = \DateTimeImmutable::createFromFormat('!Y-m-d', $originId);
        if (false === $day || $day->format('Y-m-d') !== $originId) {
            return 'origin.id must be the day of the bilan, as YYYY-MM-DD.';
        }

        if (\property_exists($state, 'step') && (!\is_string($state->step) || !\in_array($state->step, self::STEPS, true))) {
            return \sprintf('state.step must be one of %s.', implode(', ', self::STEPS));
        }

        if (\property_exists($state, 'answers')) {
            $error = self::validateAnswers($state->answers);
            if (null !== $error) {
                return $error;
            }
        }

        if (\property_exists($state, 'instructions')) {
            $error = self::validateInstructions($state->instructions);
            if (null !== $error) {
                return $error;
            }
        }

        foreach (['tomorrowTasks', 'tomorrowSpares'] as $key) {
            if (\property_exists($state, $key)) {
                $error = self::validateTomorrow($state->$key, $key);
                if (null !== $error) {
                    return $error;
                }
            }
        }

        return null;
    }

    private static function validateTomorrow(mixed $list, string $key): ?string
    {
        if (!\is_array($list)) {
            return \sprintf('state.%s must be a list.', $key);
        }
        foreach ($list as $entry) {
            if (!$entry instanceof \stdClass
                || !\is_string($entry->taskId ?? null) || '' === $entry->taskId
                || !\is_string($entry->title ?? null) || '' === $entry->title) {
                return \sprintf('Each state.%s entry needs a taskId and a title.', $key);
            }
            if (\property_exists($entry, 'reason') && (!\is_string($entry->reason) || mb_strlen($entry->reason) > self::REASON_MAX)) {
                return \sprintf('state.%s[].reason must be a string of at most %d characters.', $key, self::REASON_MAX);
            }
        }

        return null;
    }

    private static function validateAnswers(mixed $answers): ?string
    {
        if (!$answers instanceof \stdClass) {
            return 'state.answers must be a JSON object.';
        }

        if (\property_exists($answers, 'tasks')) {
            if (!\is_array($answers->tasks)) {
                return 'state.answers.tasks must be a list.';
            }
            foreach ($answers->tasks as $task) {
                if (!$task instanceof \stdClass || !\is_string($task->taskId ?? null) || '' === $task->taskId) {
                    return 'Each state.answers.tasks entry needs a taskId.';
                }
                if (!\is_string($task->choice ?? null) || !\in_array($task->choice, self::CHOICES, true)) {
                    return \sprintf('state.answers.tasks[].choice must be one of %s.', implode(', ', self::CHOICES));
                }
            }
        }

        return null;
    }

    private static function validateInstructions(mixed $instructions): ?string
    {
        if (!\is_array($instructions)) {
            return 'state.instructions must be a list.';
        }
        foreach ($instructions as $instruction) {
            if (!$instruction instanceof \stdClass || !\is_string($instruction->id ?? null) || !\is_string($instruction->text ?? null)) {
                return 'Each state.instructions entry needs an id and a text.';
            }
            if (!\is_string($instruction->kind ?? null) || !\in_array($instruction->kind, self::KINDS, true)) {
                return \sprintf('state.instructions[].kind must be one of %s.', implode(', ', self::KINDS));
            }
            if (isset($instruction->targetId) && !\is_string($instruction->targetId)) {
                return 'state.instructions[].targetId must be a string.';
            }
        }

        return null;
    }
}
