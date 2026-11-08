<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class EndErrandCommand
{
    public function __construct(
        public string $userId,
        public ?string $storeId = null,
        /** What was paid at the till of [storeId], in cents (MAG-765); null when not given. */
        public ?int $paidAmountCents = null,
    ) {
    }
}
