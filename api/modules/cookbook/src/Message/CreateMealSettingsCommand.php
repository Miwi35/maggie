<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/** Lays down a user's meal settings on first read; does nothing if they exist. */
final readonly class CreateMealSettingsCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
