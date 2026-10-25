<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

/**
 * Takes a notification seen on one device down from the trays of the others,
 * from the worker (MAG-582): a cooking timer's end acknowledged on the admin
 * must not keep ringing in a phone's pocket.
 */
final readonly class WithdrawPushNotificationCommand
{
    public function __construct(
        public string $notificationId,
    ) {
    }
}
