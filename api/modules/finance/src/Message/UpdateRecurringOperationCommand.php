<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateRecurringOperationCommand
{
    use ClearsFieldsTrait;

    /** @param list<'counterpartyName'|'labelPattern'|'endsOn'> $clearFields */
    public function __construct(
        public string $userId,
        public string $recurringOperationId,
        public ?string $label = null,
        public ?string $categoryId = null,
        public ?string $accountId = null,
        public ?int $referenceAmountCents = null,
        public ?string $anchorOn = null,
        public ?string $counterpartyName = null,
        public ?string $labelPattern = null,
        public ?string $period = null,
        public ?string $dayRule = null,
        public ?string $referenceSource = null,
        public ?int $amountTolerancePercent = null,
        public ?int $dateToleranceDays = null,
        public ?string $endsOn = null,
        public array $clearFields = [],
    ) {
    }
}
