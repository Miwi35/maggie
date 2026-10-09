<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateCategorizationRuleCommand
{
    public function __construct(
        public string $userId,
        public string $labelPattern,
        public string $categoryId,
        public string $matchType = 'contains',
        public string $direction = 'any',
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public int $priority = 0,
        public bool $isActive = true,
        public bool $applyToExisting = false,
    ) {
    }
}
