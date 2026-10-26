<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

/** What Google Routes answers for one trip: its whole duration, and its legs in order. */
final readonly class Route
{
    /** @param list<RouteLeg> $legs */
    public function __construct(
        public int $durationSeconds,
        public array $legs,
    ) {
    }
}
