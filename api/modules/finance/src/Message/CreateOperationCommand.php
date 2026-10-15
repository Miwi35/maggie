<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Finance\Entity\RecurringOperation;

/**
 * Creates an operation: what recognises its transactions, its qualification,
 * and a recurrence — period, anchorOn, referenceAmountCents and accountId
 * together — or none, for what a categorization rule was.
 */
final readonly class CreateOperationCommand
{
    /**
     * @param ?string                   $anchorOn      ISO date of the first occurrence
     * @param ?string                   $endsOn        ISO date of the last day an occurrence may fall on
     * @param array<string, mixed>|null $qualification the whole qualification (`Qualification::toArray()`); omitted, the category's deprecated flag sets it on creation or on a change of category
     */
    public function __construct(
        public string $userId,
        public string $categoryId,
        public ?string $label = null,
        public ?string $counterpartyName = null,
        public ?string $labelPattern = null,
        public string $matchType = 'contains',
        public string $direction = 'any',
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
        public int $priority = 0,
        public bool $isActive = true,
        public ?string $period = null,
        public ?string $anchorOn = null,
        public ?int $referenceAmountCents = null,
        public ?string $accountId = null,
        public string $dayRule = 'fixed_day',
        public string $referenceSource = 'measured',
        public int $amountTolerancePercent = RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
        public int $dateToleranceDays = RecurringOperation::DEFAULT_DATE_TOLERANCE_DAYS,
        public ?string $endsOn = null,
        public bool $applyToExisting = false,
        public ?array $qualification = null,
    ) {
    }
}
