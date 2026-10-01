<?php

declare(strict_types=1);

namespace Maggie\Notification\Enum;

enum NotificationType: string
{
    case Reminder = 'reminder';
    /** A bank's consent is about to run out (or has): the user has to reconnect it. */
    case ConsentExpiring = 'consent_expiring';
}
