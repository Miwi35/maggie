<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Null leaves a field untouched. */
final readonly class UpdatePlacementCommand
{
    public function __construct(
        public string $userId,
        public string $placementId,
        public ?string $name = null,
        public ?int $initial = null,
        public ?int $deposit = null,
        public ?string $frequency = null,
        public ?int $annualRate = null,
        public ?int $years = null,
    ) {
    }
}
