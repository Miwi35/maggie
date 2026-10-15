<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateCategorizationRuleCommand
{
    /** @param array<string, mixed>|null $qualification the whole qualification (`Qualification::toArray()`); omitted, the category's deprecated flag sets it on creation or on a change of category */
    public function __construct(
        public string $userId,
        public string $categoryId,
        public ?string $labelPattern = null,
        public string $matchType = 'contains',
        public string $direction = 'any',
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public int $priority = 0,
        public bool $isActive = true,
        public bool $applyToExisting = false,
        public ?array $qualification = null,
    ) {
    }
}
