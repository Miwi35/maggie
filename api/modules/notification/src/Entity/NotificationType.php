<?php

declare(strict_types=1);

namespace Maggie\Notification\Entity;

enum NotificationType: string
{
    case Reminder = 'reminder';
}
