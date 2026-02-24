<?php

namespace Maggie\Calendar\Message;

final readonly class PushTaskToGoogleCommand
{
    /**
     * @param string[] | null $changedFields
     */
    public function __construct(
        public string $taskId,
        public ?array $changedFields = null,
    ) {
    }
}
