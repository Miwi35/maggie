<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/** A categorization rule was created or modified; the caller may want it applied to the history. */
final readonly class CategorizationRuleSaved
{
    public function __construct(
        public string $ruleId,
        public bool $applyToExisting,
    ) {
    }
}
