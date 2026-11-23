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
        public ?string $delivery = null,
        public ?string $topicKey = null,
        public ?string $impact = null,
        public ?string $evidence = null,
        public ?string $deadline = null,
        public ?string $changed = null,
        public ?string $notice = null,
        public ?string $messageLink = null,
        public ?string $value = null,
    ) {
    }
}
