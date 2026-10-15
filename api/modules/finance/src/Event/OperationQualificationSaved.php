<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/**
 * An operation was modified: the transactions it qualified take its
 * qualification again, except those the owner qualified by hand.
 */
final readonly class OperationQualificationSaved
{
    public function __construct(
        public string $operationId,
    ) {
    }
}
