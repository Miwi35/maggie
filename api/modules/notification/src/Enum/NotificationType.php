<?php

declare(strict_types=1);

namespace Maggie\Notification\Enum;

enum NotificationType: string
{
    case Reminder = 'reminder';
    /** A bank's consent is about to run out (or has): the user has to reconnect it. */
    case ConsentExpiring = 'consent_expiring';
    /** Something Maggie did on her own initiative (a proaction) and wants the user to see. */
    case Proaction = 'proaction';
    /** A task reaches its due date. */
    case TaskDue = 'task_due';
    /** A grocery list needs attention. */
    case Grocery = 'grocery';
    /** An action waits for the user's approval before Maggie goes on. */
    case Approval = 'approval';
    /** A finance alert other than a bank consent (see ConsentExpiring). */
    case Finance = 'finance';
}
