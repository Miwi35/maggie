<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

/**
 * Push a stored notification to every device of its user, from the worker:
 * Google answering slowly must not hold up whoever raised the notification.
 */
final readonly class SendPushNotificationCommand
{
    public function __construct(
        public string $notificationId,
    ) {
    }
}
