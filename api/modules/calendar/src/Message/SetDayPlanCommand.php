<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/**
 * Replaces the day's slots, the whole list (MAG-497). `confirm` settles the
 * day when it is not yet: a change by hand does, a proposal does not.
 */
final readonly class SetDayPlanCommand
{
    /** @param list<mixed> $slots each {type, taskId}, in order */
    public function __construct(
        public string $userId,
        public string $dayPlanId,
        public array $slots,
        public bool $confirm = true,
    ) {
    }
}
