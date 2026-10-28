<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Location\LocationFix;
use Maggie\Core\Repository\UserLocationRepository;
use Psr\Clock\ClockInterface;

/**
 * The single place that says where the user is (MAG-666). The GPS position is the default; the
 * network one serves only when there is no GPS position, or when the GPS one is older than
 * {@see self::GPS_STALE_HOURS} hours and the network one is newer. The trip origin, the trip planner,
 * the replanning and the MCP tools all read it here.
 */
class UserPositions
{
    public const int GPS_STALE_HOURS = 2;

    public function __construct(
        private readonly UserLocationRepository $locations,
        private readonly ClockInterface $clock,
    ) {
    }

    public function of(User $user): ?LocationFix
    {
        $location = $this->locations->findOneByUser($user);

        return null === $location ? null : $this->chosen($location);
    }

    public function chosen(UserLocation $location): ?LocationFix
    {
        $gps = $location->getGpsFix();
        $network = $location->getNetworkFix();
        if (null === $gps || null === $network) {
            return $gps ?? $network;
        }

        $staleBefore = $this->clock->now()->modify(sprintf('-%d hours', self::GPS_STALE_HOURS));

        return $gps->capturedAt < $staleBefore && $network->capturedAt > $gps->capturedAt ? $network : $gps;
    }
}
