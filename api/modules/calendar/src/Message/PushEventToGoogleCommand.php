<?php

namespace Maggie\Calendar\Message;

final readonly class PushEventToGoogleCommand
{
    /**
     * @param string[]|null $changedFields
     */
    public function __construct(
        public string $eventId,
        public string $action, // 'create', 'update' or 'move'
        public ?array $changedFields = null,
        public ?string $fromGoogleCalendarId = null, // 'move': the Google calendar the event leaves
    ) {
    }
}
