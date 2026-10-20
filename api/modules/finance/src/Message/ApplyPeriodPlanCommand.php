<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * Writes the plan of a period in one gesture (MAG-462): every item checked
 * before the first write, one flush. An item is `{kind: annual|monthly|one_off|event, …}`,
 * see {@see \Maggie\Finance\UseCase\ApplyPeriodPlan}.
 */
final readonly class ApplyPeriodPlanCommand
{
    /** @param list<mixed> $items */
    public function __construct(
        public string $userId,
        public string $period,
        public array $items,
    ) {
    }
}
