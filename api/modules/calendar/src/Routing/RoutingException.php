<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

/** Google Routes or Geocoding could not answer: no key, unreachable, or an error. */
class RoutingException extends \RuntimeException
{
}
