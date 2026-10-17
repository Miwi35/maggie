<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * « Tom m'avance 400 €, dont 280 € à lui rendre »: declares a received transfer
 * as a dette towards a contact, for the amount to give back.
 */
final readonly class RecordDebtCommand
{
    public function __construct(
        public string $userId,
        public string $transactionId,
        public string $contactId,
        public int $amountCents,
        public ?string $object = null,
        public ?string $notes = null,
    ) {
    }
}
