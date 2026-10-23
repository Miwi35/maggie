<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class MarkFinanceDashboardStaleCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
