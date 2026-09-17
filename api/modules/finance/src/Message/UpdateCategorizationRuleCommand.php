<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class UpdateCategorizationRuleCommand
{
    public function __construct(
        public string $categorizationRuleId,
        public ?string $labelPattern = null,
        public ?string $categoryId = null,
        public ?string $matchType = null,
        public ?string $direction = null,
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public ?int $priority = null,
        public ?bool $isActive = null,
    ) {
    }
}
