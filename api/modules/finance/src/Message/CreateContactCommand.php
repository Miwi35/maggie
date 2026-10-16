<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateContactCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public ?string $notes = null,
    ) {
    }
}
