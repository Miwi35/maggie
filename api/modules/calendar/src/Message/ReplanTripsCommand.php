<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/**
 * Wake Maggie to plan the day's trips again (MAG-656).
 *
 * `reason` is one of the constants below. The handler files at most one pending
 * « Replanifier les trajets » proaction per user.
 */
final readonly class ReplanTripsCommand
{
    /** What the agenda reads, and what tells a replan proaction from any other. */
    public const SUMMARY = 'Replanifier les trajets';

    public const EVENT_CREATED = 'event_created';
    public const EVENT_UPDATED = 'event_updated';
    public const EVENT_DELETED = 'event_deleted';
    public const POSITION_MOVED = 'position_moved';
    public const CAR_MOVED = 'car_moved';

    public function __construct(
        public string $userId,
        public string $reason,
    ) {
    }
}
