<?php

declare(strict_types=1);

namespace Maggie\Core\Reminder;

use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * A module's reminders. `maggie:notification:check-reminders` runs every
 * source each minute and knows none of them: a module that has something to
 * remind implements this, nothing else to declare.
 */
#[AutoconfigureTag(ReminderSourceInterface::TAG)]
interface ReminderSourceInterface
{
    public const TAG = 'maggie.reminder_source';

    /**
     * Creates the notifications due at `$now`, for every user.
     *
     * A failure of one user must not cost the others their reminders: it goes in
     * the report's warnings, not in an exception.
     */
    public function remind(\DateTimeImmutable $now): ReminderReport;
}
