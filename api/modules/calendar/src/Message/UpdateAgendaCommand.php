<?php

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateAgendaCommand
{
    use ClearsFieldsTrait;

    /**
     * @param list<'description'|'color'> $clearFields
     * @param bool                        $fromGoogle  the name comes from Google (a sync, or a rename Google refused being undone): it is stored, not sent back
     */
    public function __construct(
        public string $agendaId,
        public ?string $name = null,
        public ?string $description = null,
        public ?string $timeZone = null,
        public ?string $color = null,
        public ?bool $isDefault = null,
        public array $clearFields = [],
        public bool $fromGoogle = false,
    ) {
    }
}
