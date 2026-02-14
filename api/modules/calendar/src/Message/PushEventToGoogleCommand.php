<?php

namespace Maggie\Calendar\Message;

final readonly class PushEventToGoogleCommand
{
    public function __construct(
        public string $eventId,
        public string $action, // 'create' or 'update'
    ) {
    }
}
