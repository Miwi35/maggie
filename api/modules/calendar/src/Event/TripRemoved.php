<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/** A trip no longer exists (MAG-679), whether it was removed alone or with its event. */
final readonly class TripRemoved
{
    public function __construct(
        public string $tripId,
    ) {
    }

    /** The IRI the notifications about it carry as `relatedEntityIri`. */
    public function iri(): string
    {
        return '/api/trips/'.$this->tripId;
    }
}
