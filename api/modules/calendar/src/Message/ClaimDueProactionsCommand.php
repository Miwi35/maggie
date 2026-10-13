<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** No user: the executor claims for everyone (MAG-326). A proaction id: that one, due or not (MAG-327). */
final readonly class ClaimDueProactionsCommand
{
    public function __construct(
        public ?string $userId,
        public int $limit,
        public ?string $proactionId = null,
    ) {
    }
}
