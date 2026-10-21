<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreatePlacementCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public int $initial,
        public int $deposit,
        public string $frequency,
        public int $annualRate,
        public int $years,
    ) {
    }
}
