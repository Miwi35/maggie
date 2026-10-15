<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateCategorizationRuleCommand
{
    use ClearsFieldsTrait;

    /**
     * @param list<'minAmountCents'|'maxAmountCents'> $clearFields
     * @param array<string, mixed>|null               $qualification the whole qualification (`Qualification::toArray()`); omitted, the category's deprecated flag sets it on creation or on a change of category
     */
    public function __construct(
        public string $userId,
        public string $categorizationRuleId,
        public ?string $labelPattern = null,
        public ?string $categoryId = null,
        public ?string $matchType = null,
        public ?string $direction = null,
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public ?int $priority = null,
        public ?bool $isActive = null,
        public array $clearFields = [],
        public bool $applyToExisting = false,
        public ?array $qualification = null,
    ) {
    }
}
