<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

/** The user says they are sick, or that it is over (MAG-810). */
final readonly class SetSickCommand
{
    public function __construct(
        public string $userId,
        public bool $sick,
    ) {
    }
}
