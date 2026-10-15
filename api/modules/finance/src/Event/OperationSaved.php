<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/** An operation was created or modified; the caller may want it applied to the history. */
final readonly class OperationSaved
{
    public function __construct(
        public string $operationId,
        public bool $applyToExisting,
    ) {
    }
}
