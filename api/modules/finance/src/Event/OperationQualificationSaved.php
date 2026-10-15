<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;

/**
 * A recurring operation or a categorization rule was modified: the
 * transactions it qualified take its qualification again, except those the
 * owner qualified by hand.
 */
final readonly class OperationQualificationSaved
{
    /** @param class-string<RecurringOperation|CategorizationRule> $operationClass */
    public function __construct(
        public string $operationClass,
        public string $operationId,
    ) {
    }
}
