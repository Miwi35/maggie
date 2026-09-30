<?php

namespace Maggie\Calendar\Message;

final readonly class PushEventToGoogleCommand
{
    /**
     * @param string[]|null $changedFields
     */
    public function __construct(
        public string $eventId,
        public string $action, // 'create' or 'update'
        public ?array $changedFields = null,
    ) {
    }
}
