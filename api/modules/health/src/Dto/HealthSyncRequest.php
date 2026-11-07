<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/**
 * The body of `POST /api/health/sync` (MAG-708). Its items are built from the
 * JSON as they come, untyped, so that a wrong type is a violation with its
 * path (`workouts[2].startedAt`) and never a 500: the whole batch is refused
 * on the first one, before anything is written.
 */
final class HealthSyncRequest
{
    /**
     * @param list<SyncedWorkout>     $workouts
     * @param list<SyncedMeasurement> $measurements
     * @param list<SyncedDay>         $days
     */
    public function __construct(
        #[Assert\Valid]
        public readonly array $workouts = [],
        #[Assert\Valid]
        public readonly array $measurements = [],
        #[Assert\Valid]
        public readonly array $days = [],
    ) {
    }

    /**
     * Null when the body is not an object of lists of objects; the message says why.
     *
     * @param array<mixed> $body
     */
    public static function fromBody(array $body, ?string &$error = null): ?self
    {
        $lists = [];
        foreach (['workouts' => SyncedWorkout::class, 'measurements' => SyncedMeasurement::class, 'days' => SyncedDay::class] as $key => $class) {
            $items = $body[$key] ?? [];
            if (!\is_array($items) || !array_is_list($items)) {
                $error = "{$key}: must be a list.";

                return null;
            }
            $lists[$key] = [];
            foreach ($items as $index => $item) {
                if (!\is_array($item) || ([] !== $item && array_is_list($item))) {
                    $error = "{$key}[{$index}]: must be an object.";

                    return null;
                }
                $lists[$key][] = $class::fromArray($item);
            }
        }

        $unknown = array_diff(array_keys($body), ['workouts', 'measurements', 'days']);
        if ([] !== $unknown) {
            $error = 'Unknown field(s): '.implode(', ', $unknown).'. Known fields: workouts, measurements, days.';

            return null;
        }

        return new self($lists['workouts'], $lists['measurements'], $lists['days']);
    }

    /** An externalId or a date twice in one batch would be written twice, the second over the first. */
    #[Assert\Callback]
    public function validateUniqueness(ExecutionContextInterface $context): void
    {
        foreach (['workouts' => 'externalId', 'measurements' => 'externalId', 'days' => 'date'] as $list => $key) {
            $seen = [];
            foreach ($this->{$list} as $index => $item) {
                $value = $item->{$key};
                if (!\is_string($value)) {
                    continue;
                }
                if (isset($seen[$value])) {
                    $context->buildViolation("Already given at {$list}[{$seen[$value]}].")->atPath("{$list}[{$index}].{$key}")->addViolation();
                }
                $seen[$value] ??= $index;
            }
        }
    }
}
