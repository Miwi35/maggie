<?php

declare(strict_types=1);

namespace Maggie\Core\Event;

/**
 * The owner's last position, or the car's place, changed (MAG-650) — the row's
 * creation included, a re-sent identical report excluded.
 */
final readonly class UserLocationChangedEvent
{
    public const POSITION = 'position';
    public const CAR = 'car';

    /** @param self::POSITION|self::CAR $what */
    public function __construct(
        public string $userId,
        public string $what,
    ) {
    }
}
