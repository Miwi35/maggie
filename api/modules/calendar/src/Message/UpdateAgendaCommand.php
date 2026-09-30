<?php

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateAgendaCommand
{
    use ClearsFieldsTrait;

    /** @param list<'description'|'color'> $clearFields */
    public function __construct(
        public string $agendaId,
        public ?string $name = null,
        public ?string $description = null,
        public ?string $timeZone = null,
        public ?string $color = null,
        public ?bool $isDefault = null,
        public array $clearFields = [],
    ) {
    }
}
