<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

/** Google Geocoding found nothing at an address: the address is the user's to fix, not an outage. */
final class AddressNotFoundException extends RoutingException
{
    public function __construct(public readonly string $address)
    {
        parent::__construct(sprintf('No place found at "%s".', $address));
    }
}
