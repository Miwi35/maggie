<?php

namespace Maggie\Core\Message;

final readonly class UpdateUserCommand
{
    public function __construct(
        public string $userId,
        public ?string $name = null,
        public ?string $avatar = null,
    ) {
    }
}
