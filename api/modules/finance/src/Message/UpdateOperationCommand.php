<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

/**
 * Changes the fields given and leaves the others alone; `clearFields` empties
 * the optional ones. An operation keeps the recurrence it was created with, or
 * its lack of one: a series' lines are attached to it, a rule's are not.
 */
final readonly class UpdateOperationCommand
{
    use ClearsFieldsTrait;

    public const array CLEARABLE = ['label', 'counterpartyName', 'labelPattern', 'minAmountCents', 'maxAmountCents', 'endsOn'];

    /**
     * @param list<string>              $clearFields   among CLEARABLE
     * @param array<string, mixed>|null $qualification the whole qualification (`Qualification::toArray()`); omitted, the category's deprecated flag sets it on creation or on a change of category
     */
    public function __construct(
        public string $userId,
        public string $operationId,
        public ?string $categoryId = null,
        public ?string $label = null,
        public ?string $counterpartyName = null,
        public ?string $labelPattern = null,
        public ?string $matchType = null,
        public ?string $direction = null,
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public ?int $priority = null,
        public ?bool $isActive = null,
        public ?string $period = null,
        public ?string $anchorOn = null,
        public ?int $referenceAmountCents = null,
        public ?string $accountId = null,
        public ?string $dayRule = null,
        public ?string $referenceSource = null,
        public ?int $amountTolerancePercent = null,
        public ?int $dateToleranceDays = null,
        public ?string $endsOn = null,
        public array $clearFields = [],
        public bool $applyToExisting = false,
        public ?array $qualification = null,
    ) {
    }
}
