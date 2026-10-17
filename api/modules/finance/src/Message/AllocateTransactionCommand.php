<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * « Ce virement est… » (MAG-453): what a transfer with a person is, and the
 * shares of créances or dettes it gives back.
 */
final readonly class AllocateTransactionCommand
{
    /**
     * @param list<array{shareId: string, amountCents: int}>                                    $allocations
     * @param array{contactId: ?string, amountCents: int, object: ?string, notes: ?string}|null $debt
     * @param array{contactId: ?string, object: ?string, notes: ?string}|null                   $claim
     */
    public function __construct(
        public string $userId,
        public string $transactionId,
        public string $kind,
        public array $allocations = [],
        public ?array $debt = null,
        public ?array $claim = null,
    ) {
    }
}
