<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

final readonly class DeletePeriodCommand
{
    public function __construct(
        public string $userId,
        public string $periodId,
    ) {
    }
}
