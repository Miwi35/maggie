<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

final readonly class CreateNotificationCommand
{
    public function __construct(
        public string $type,
        public string $title,
        public ?string $body = null,
        public ?string $relatedEntityIri = null,
        public ?string $userId = null,
    ) {
    }
}
