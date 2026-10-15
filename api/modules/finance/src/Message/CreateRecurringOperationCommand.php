<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Finance\Entity\RecurringOperation;

final readonly class CreateRecurringOperationCommand
{
    /**
     * @param string                    $anchorOn      ISO date of the first occurrence
     * @param ?string                   $endsOn        ISO date of the last day an occurrence may fall on
     * @param array<string, mixed>|null $qualification the whole qualification (`Qualification::toArray()`); omitted, the category's deprecated flag sets it on creation or on a change of category
     */
    public function __construct(
        public string $userId,
        public string $label,
        public string $categoryId,
        public string $accountId,
        public int $referenceAmountCents,
        public string $anchorOn,
        public ?string $counterpartyName = null,
        public ?string $labelPattern = null,
        public string $period = 'monthly',
        public string $dayRule = 'fixed_day',
        public string $referenceSource = 'measured',
        public int $amountTolerancePercent = RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
        public int $dateToleranceDays = RecurringOperation::DEFAULT_DATE_TOLERANCE_DAYS,
        public ?string $endsOn = null,
        public ?array $qualification = null,
    ) {
    }
}
