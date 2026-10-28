<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

/**
 * What a notification is about is gone (MAG-679): every one still worth saying about that entity stops
 * being so now. They stay in the bell as history; the screens that interrupt drop them.
 */
final readonly class ExpireNotificationsCommand
{
    public function __construct(
        public string $relatedEntityIri,
    ) {
    }
}
