<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

final readonly class MarkNotificationReadCommand
{
    public function __construct(
        public string $notificationId,
        public ?\DateTimeImmutable $readAt = null,
    ) {
    }
}
