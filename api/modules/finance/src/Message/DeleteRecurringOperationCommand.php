<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DeleteRecurringOperationCommand
{
    public function __construct(
        public string $userId,
        public string $recurringOperationId,
    ) {
    }
}
