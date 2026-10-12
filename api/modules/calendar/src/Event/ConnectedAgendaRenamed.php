<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/**
 * An agenda connected to Google was renamed in Maggie.
 *
 * `previousName` is a snapshot, not an entity: if Google cannot be told, the agenda goes back to it,
 * and Google may be the very thing that cannot be reached to ask what it was.
 */
final readonly class ConnectedAgendaRenamed
{
    public function __construct(
        public string $agendaId,
        public string $previousName,
    ) {
    }
}
