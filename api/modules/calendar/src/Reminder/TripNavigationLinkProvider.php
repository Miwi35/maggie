<?php

declare(strict_types=1);

namespace Maggie\Calendar\Reminder;

use Maggie\Calendar\Repository\TripRepository;
use Maggie\Notification\Push\NavigationLinkProvider;

/** The « Y aller » of a departure alert: the navigation URL kept on the trip (MAG-655). */
final class TripNavigationLinkProvider implements NavigationLinkProvider
{
    public function __construct(private readonly TripRepository $tripRepository)
    {
    }

    public function navigationUrl(string $relatedEntityIri): ?string
    {
        if (1 !== preg_match('#^/api/trips/([0-9A-Za-z-]{1,64})$#', $relatedEntityIri, $m)) {
            return null;
        }

        try {
            return $this->tripRepository->find($m[1])?->getNavigationUrl();
        } catch (\Throwable) {
            // An id that is no ULID: not one of ours.
            return null;
        }
    }
}
