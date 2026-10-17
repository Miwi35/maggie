<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Gives the existing history to one operation: the lines it would have taken itself. */
final readonly class ApplyOperationCommand
{
    public function __construct(
        public string $operationId,
    ) {
    }
}
