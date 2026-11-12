<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Repository\WorkoutMakeupRepository;
use Maggie\Notification\Push\PushDataProvider;

/** The make-up and the minutes of its buttons, in the push, so the phone words « 45 min, plus doux » and answers without a call to read (MAG-772). */
final class WorkoutMakeupPushData implements PushDataProvider
{
    public function __construct(private readonly WorkoutMakeupRepository $makeups)
    {
    }

    public function dataFor(string $relatedEntityIri): array
    {
        if (1 !== preg_match('#^/api/workout-makeups/([0-9A-Za-z-]{1,64})$#', $relatedEntityIri, $m)) {
            return [];
        }

        try {
            $makeup = $this->makeups->find($m[1]);
        } catch (\Throwable) {
            // An id that is no ULID: not one of ours.
            return [];
        }

        return null === $makeup ? [] : [
            'makeupId' => $m[1],
            'softerMinutes' => (string) $makeup->getSofterMinutes(),
            'shorterMinutes' => (string) $makeup->getShorterMinutes(),
        ];
    }
}
