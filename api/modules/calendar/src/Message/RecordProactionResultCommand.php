<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** No user: the executor records for everyone (MAG-326). */
final readonly class RecordProactionResultCommand
{
    public function __construct(
        public ?string $userId,
        public string $proactionId,
        public string $status,
        public ?string $response = null,
        public ?string $error = null,
    ) {
    }
}
