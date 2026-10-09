<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateTransactionCommand
{
    use ClearsFieldsTrait;

    /**
     * `recurringOperationId` attaches the line by hand, to `recurringOccurrenceOn`
     * or to the occurrence nearest its booking day; `recurringOccurrenceOn`
     * alone moves it within its series; clearing `recurringOperation` detaches it.
     *
     * @param list<'categoryId'|'transferKind'|'recurringOperation'> $clearFields
     */
    public function __construct(
        public string $userId,
        public string $transactionId,
        public ?string $accountId = null,
        public ?int $amountCents = null,
        public ?string $label = null,
        public ?string $bookedAt = null,
        public ?string $status = null,
        public ?string $currency = null,
        public ?bool $isExceptional = null,
        public ?string $categoryId = null,
        public ?string $categorySource = null,
        public ?string $retrospect = null,
        public ?string $transferKind = null,
        public ?string $transferSource = null,
        public ?string $counterpartId = null,
        public ?string $recurringOperationId = null,
        public ?string $recurringOccurrenceOn = null,
        public array $clearFields = [],
    ) {
    }
}
