<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Creates a place, or changes the one `placeId` names (MAG-652). */
final readonly class SavePlaceCommand
{
    /** @param array{name?: ?string, address?: ?string, latitude?: ?float, longitude?: ?float, parkingAddress?: ?string, parkingLatitude?: ?float, parkingLongitude?: ?float, isHome?: ?bool} $fields */
    public function __construct(
        public string $userId,
        public ?string $placeId,
        public array $fields,
    ) {
    }
}
