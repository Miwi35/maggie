<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Lays down a user's task settings on first read; does nothing if they exist. */
final readonly class CreateTaskSettingsCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
