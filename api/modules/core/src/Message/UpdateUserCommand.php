<?php

namespace Maggie\Core\Message;

final readonly class UpdateUserCommand
{
    use ClearsFieldsTrait;

    /** @param list<'avatar'> $clearFields */
    public function __construct(
        public string $userId,
        public ?string $name = null,
        public ?string $avatar = null,
        public array $clearFields = [],
    ) {
    }
}
