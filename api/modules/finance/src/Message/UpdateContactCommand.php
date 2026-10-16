<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateContactCommand
{
    use ClearsFieldsTrait;

    /** @param list<'notes'> $clearFields */
    public function __construct(
        public string $userId,
        public string $contactId,
        public ?string $name = null,
        public ?string $notes = null,
        public array $clearFields = [],
    ) {
    }
}
