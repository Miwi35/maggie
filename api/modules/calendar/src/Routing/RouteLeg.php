<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

use Maggie\Calendar\Enum\TripMode;

/** A stretch of a route done one way — on foot, or on one transit line, named by `label`. */
final readonly class RouteLeg
{
    public function __construct(
        public TripMode $kind,
        public int $seconds,
        public ?string $label = null,
    ) {
    }
}
