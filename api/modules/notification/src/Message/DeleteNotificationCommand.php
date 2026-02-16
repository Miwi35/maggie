<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

final readonly class DeleteNotificationCommand
{
    public function __construct(
        public string $notificationId,
    ) {
    }
}
