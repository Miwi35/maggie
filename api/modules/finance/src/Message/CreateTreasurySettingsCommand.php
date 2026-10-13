<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Lays down a user's treasury settings on first read; does nothing if they exist. */
final readonly class CreateTreasurySettingsCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
