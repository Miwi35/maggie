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
     * `repaysTransactionId` points the debit leg of a `repayment` at the advance
     * it gives back. `oneOffEnvelopeId` attributes the spend to a one-off
     * envelope; clearing `oneOffEnvelope` takes it back to its category's envelopes.
     *
     * `qualification` is the owner's own, whole (`Qualification::toArray()`),
     * with `categoryId`, or the current category when it is omitted.
     * `qualifiedByRuleId` instead files the line with the whole qualification
     * of that rule.
     *
     * @param array<string, mixed>|null                                                      $qualification
     * @param list<'categoryId'|'transferKind'|'recurringOperation'|'note'|'oneOffEnvelope'> $clearFields
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
        public ?array $qualification = null,
        public ?string $qualifiedByRuleId = null,
        public ?string $retrospect = null,
        public ?string $transferKind = null,
        public ?string $transferSource = null,
        public ?string $counterpartId = null,
        public ?string $repaysTransactionId = null,
        public ?string $recurringOperationId = null,
        public ?string $recurringOccurrenceOn = null,
        public ?string $note = null,
        public ?string $oneOffEnvelopeId = null,
        public array $clearFields = [],
    ) {
    }
}
